package edu.columbia.gskr.sentimentmap.rest.service;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.owlike.genson.Genson;
import edu.columbia.gskr.sentimentmap.domain.TweetSentiment;
import edu.columbia.gskr.sentimentmap.mybatis.service.TweetService;
import edu.columbia.gskr.sentimentmap.rest.domain.SNSMessage;
import edu.columbia.gskr.sentimentmap.util.SNSHelper;
import edu.columbia.gskr.sentimentmap.websockets.globalvariables.GlobalVariables;
import org.apache.commons.codec.binary.Base64;

import javax.servlet.http.HttpServletRequest;
import javax.websocket.EncodeException;
import javax.websocket.Session;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Scanner;

/**
 * Created by saikarthikreddyginni on 2/27/15.
 */


@Path("/tweets")
public class TweetRestService {

    private final TweetService tweetService = new TweetService();
    private AWSCredentials credentials = new ProfileCredentialsProvider("default").getCredentials();

    @GET
    @Produces({MediaType.APPLICATION_JSON})
    public List<TweetSentiment> getTweetsLimit(@QueryParam("limit") int limit, @QueryParam("hashTag") List<String> hashTags) {

        if (hashTags != null && hashTags.size() > 0) {
            return tweetService.getTweetsByHashTag(hashTags);
        } else if (limit != 0) {
            return tweetService.getTweetsLimit(limit);
        } else {
            return tweetService.getAllTweets();
        }
    }

    @GET
    @Path("/{id}")
    @Produces({MediaType.APPLICATION_JSON})
    public TweetSentiment getTweetById(@PathParam("id") long id) {
        return tweetService.getTweetById(id);
    }

    @GET
    @Path("/hashTags")
    @Produces({MediaType.APPLICATION_JSON})
    public List<String> getHashTagsLimit(@QueryParam("limit") int limit) {
        if (limit != 0) {
            return tweetService.getHashTagsLimit(limit);
        } else {
            return tweetService.getAllHashTags();
        }
    }

    @GET
    @Path("/tweetCount")
    @Produces({MediaType.APPLICATION_JSON})
    public int getTweetCount() {
        return tweetService.getTweetCount();
    }

    @POST
    @Path("/sns")
    @Consumes({MediaType.APPLICATION_JSON})
    public void processSNSTweet(@Context HttpServletRequest request) {

        //Get the message type header.
        String messagetype = request.getHeader("x-amz-sns-message-type");
        //If message doesn't have the message type header, don't process it.
        if (messagetype == null)
            return;

        // Parse the JSON message in the message body
        // and hydrate a Message object with its contents
        // so that we have easy access to the name/value pairs
        // from the JSON message.
        Scanner scan = null;
        try {
            scan = new Scanner(request.getInputStream());
            StringBuilder builder = new StringBuilder();
            while (scan.hasNextLine()) {
                builder.append(scan.nextLine());
            }

            Genson genson = new Genson();

            SNSMessage msg = genson.deserialize(builder.toString(), SNSMessage.class);

            // The signature is based on SignatureVersion 1.
            // If the sig version is something other than 1,
            // throw an exception.
            if (msg.getSignatureVersion().equals("1")) {
                // Check the signature and throw an exception if the signature verification fails.
                if (isMessageSignatureValid(msg))
                    System.out.println(">>Signature verification succeeded");
                else {
                    System.out.println(">>Signature verification failed");
                    throw new SecurityException("Signature verification failed.");
                }
            } else {
                System.out.println(">>Unexpected signature version. Unable to verify signature.");
                throw new SecurityException("Unexpected signature version. Unable to verify signature.");
            }

            // Process the message based on type.
            if (messagetype.equals("Notification")) {
                //TODO: Do something with the Message and Subject.
                //Just log the subject (if it exists) and the message.
                String logMsgAndSubject = ">>Notification received from topic " + msg.getTopicArn();
                if (msg.getSubject() != null) {
                    logMsgAndSubject += " Subject: " + msg.getSubject();
                }
                logMsgAndSubject += " Message: " + msg.getMessage();
                String mes = msg.getMessage();

                TweetSentiment tweetSentiment = genson.deserialize(mes, TweetSentiment.class);

                for (Session session : GlobalVariables.sessions){
                    session.getBasicRemote().sendObject(tweetSentiment);
                }

                System.out.println(logMsgAndSubject);

            } else if (messagetype.equals("SubscriptionConfirmation")) {
                //TODO: You should make sure that this subscription is from the topic you expect. Compare topicARN to your list of topics
                //that you want to enable to add this endpoint as a subscription.

                //Confirm the subscription by going to the subscribeURL location
                //and capture the return value (XML message body as a string)
                Scanner sc = null;
                try {
                    sc = new Scanner(new URL(msg.getSubscribeURL()).openStream());
                    StringBuilder sb = new StringBuilder();
                    while (sc.hasNextLine()) {
                        sb.append(sc.nextLine());
                    }
                    System.out.println(">>Subscription confirmation (" + msg.getSubscribeURL() + ") Return value: " + sb.toString());
                    //TODO: Process the return value to ensure the endpoint is subscribed.
                    SNSHelper.INSTANCE.confirmTopicSubmission(msg);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (messagetype.equals("UnsubscribeConfirmation")) {
                //TODO: Handle UnsubscribeConfirmation message.
                //For example, take action if unsubscribing should not have occurred.
                //You can read the SubscribeURL from this message and
                //re-subscribe the endpoint.
                System.out.println(">>Unsubscribe confirmation: " + msg.getMessage());
            } else {
                //TODO: Handle unknown message type.
                System.out.println(">>Unknown message type.");
            }
            System.out.println(">>Done processing message: " + msg.getMessageId());
        } catch (IOException e) {
            e.printStackTrace();
        } catch (EncodeException e) {
            e.printStackTrace();
        }
    }


    private boolean isMessageSignatureValid(SNSMessage msg) {

        try {
            URL url = new URL(msg.getSigningCertUrl());
            InputStream inStream = url.openStream();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(inStream);
            inStream.close();

            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initVerify(cert.getPublicKey());
            sig.update(getMessageBytesToSign(msg));
            return sig.verify(Base64.decodeBase64(msg.getSignature().getBytes()));
        } catch (Exception e) {
            throw new SecurityException("Verify method failed.", e);

        }
    }

    private byte[] getMessageBytesToSign(SNSMessage msg) {

        byte[] bytesToSign = null;
        if (msg.getType().equals("Notification"))
            bytesToSign = buildNotificationStringToSign(msg).getBytes();
        else if (msg.getType().equals("SubscriptionConfirmation") || msg.getType().equals("UnsubscribeConfirmation"))
            bytesToSign = buildSubscriptionStringToSign(msg).getBytes();
        return bytesToSign;
    }

    //Build the string to sign for Notification messages.
    private static String buildNotificationStringToSign(SNSMessage msg) {
        String stringToSign = null;

        //Build the string to sign from the values in the message.
        //Name and values separated by newline characters
        //The name value pairs are sorted by name
        //in byte sort order.
        stringToSign = "Message\n";
        stringToSign += msg.getMessage() + "\n";
        stringToSign += "MessageId\n";
        stringToSign += msg.getMessageId() + "\n";
        if (msg.getSubject() != null) {
            stringToSign += "Subject\n";
            stringToSign += msg.getSubject() + "\n";
        }
        stringToSign += "Timestamp\n";
        stringToSign += msg.getTimestamp() + "\n";
        stringToSign += "TopicArn\n";
        stringToSign += msg.getTopicArn() + "\n";
        stringToSign += "Type\n";
        stringToSign += msg.getType() + "\n";
        return stringToSign;
    }

    //Build the string to sign for SubscriptionConfirmation
    //and UnsubscribeConfirmation messages.
    private static String buildSubscriptionStringToSign(SNSMessage msg) {
        String stringToSign = null;
        //Build the string to sign from the values in the message.
        //Name and values separated by newline characters
        //The name value pairs are sorted by name
        //in byte sort order.
        stringToSign = "Message\n";
        stringToSign += msg.getMessage() + "\n";
        stringToSign += "MessageId\n";
        stringToSign += msg.getMessageId() + "\n";
        stringToSign += "SubscribeURL\n";
        stringToSign += msg.getSubscribeURL() + "\n";
        stringToSign += "Timestamp\n";
        stringToSign += msg.getTimestamp() + "\n";
        stringToSign += "Token\n";
        stringToSign += msg.getToken() + "\n";
        stringToSign += "TopicArn\n";
        stringToSign += msg.getTopicArn() + "\n";
        stringToSign += "Type\n";
        stringToSign += msg.getType() + "\n";
        return stringToSign;
    }

}