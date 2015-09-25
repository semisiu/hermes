package pl.allegro.tech.hermes.consumers.consumer;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.allegro.tech.hermes.api.Subscription;
import pl.allegro.tech.hermes.api.Topic;
import pl.allegro.tech.hermes.common.metric.HermesMetrics;
import pl.allegro.tech.hermes.consumers.consumer.converter.MessageConverter;
import pl.allegro.tech.hermes.consumers.consumer.offset.SubscriptionOffsetCommitQueues;
import pl.allegro.tech.hermes.consumers.consumer.rate.ConsumerRateLimiter;
import pl.allegro.tech.hermes.consumers.consumer.receiver.MessageReceiver;
import pl.allegro.tech.hermes.consumers.consumer.receiver.MessageReceivingTimeoutException;
import pl.allegro.tech.hermes.common.kafka.offset.PartitionOffset;
import pl.allegro.tech.hermes.tracker.consumers.Trackers;

import java.util.List;
import java.util.concurrent.Semaphore;

import static pl.allegro.tech.hermes.consumers.consumer.message.MessageConverter.toMessageMetadata;

public class Consumer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(Consumer.class);

    private final MessageReceiver messageReceiver;
    private final HermesMetrics hermesMetrics;
    private final ConsumerRateLimiter rateLimiter;
    private final SubscriptionOffsetCommitQueues subscriptionOffsetCommitQueues;
    private final Semaphore inflightSemaphore;
    private final Trackers trackers;
    private final MessageConverter messageConverter;
    private final Topic topic;
    private final ConsumerMessageSender sender;

    private Subscription subscription;

    private volatile boolean consuming = true;

    public Consumer(MessageReceiver messageReceiver, HermesMetrics hermesMetrics, Subscription subscription,
                    ConsumerRateLimiter rateLimiter, SubscriptionOffsetCommitQueues subscriptionOffsetCommitQueues,
                    ConsumerMessageSender sender, Semaphore inflightSemaphore, Trackers trackers, MessageConverter messageConverter, Topic topic) {
        this.messageReceiver = messageReceiver;
        this.hermesMetrics = hermesMetrics;
        this.subscription = subscription;
        this.rateLimiter = rateLimiter;
        this.subscriptionOffsetCommitQueues = subscriptionOffsetCommitQueues;
        this.sender = sender;
        this.inflightSemaphore = inflightSemaphore;
        this.trackers = trackers;
        this.messageConverter = messageConverter;
        this.topic = topic;
    }

    private String getId() {
        return subscription.getId();
    }

    @Override
    public void run() {
        setThreadName();
        rateLimiter.initialize();
        while (isConsuming()) {
            try {
                inflightSemaphore.acquire();

                Message message = messageReceiver.next();

                Message convertedMessage = messageConverter.convert(message, topic);

                sendMessage(convertedMessage);
            } catch (MessageReceivingTimeoutException messageReceivingTimeoutException) {
                inflightSemaphore.release();
                logger.debug("Timeout while reading message from topic. Trying to read message again", messageReceivingTimeoutException);
            } catch (Exception e) {
                logger.error("Consumer loop failed for " + getId(), e);
            }
        }
        logger.info("Stopping consumer for subscription {}", subscription.getId());
        messageReceiver.stop();
    }

    private void sendMessage(Message message) {
        subscriptionOffsetCommitQueues.put(message);

        hermesMetrics.incrementInflightCounter(subscription);
        trackers.get(subscription).logInflight(toMessageMetadata(message, subscription));

        sender.sendMessage(message);
    }

    public void stopConsuming() {
        rateLimiter.shutdown();
        sender.shutdown();
        consuming = false;
    }

    public List<PartitionOffset> getOffsetsToCommit() {
        return subscriptionOffsetCommitQueues.getOffsetsToCommit();
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public void updateSubscription(Subscription newSubscription) {
        rateLimiter.updateSubscription(newSubscription);
        sender.updateSubscription(newSubscription);
        this.subscription = newSubscription;
    }

    private void setThreadName() {
        Thread.currentThread().setName("Consumer-" + subscription.getId());
    }

    @VisibleForTesting
    protected boolean isConsuming() {
        return consuming;
    }

}
