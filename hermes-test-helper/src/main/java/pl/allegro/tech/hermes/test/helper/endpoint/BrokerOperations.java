package pl.allegro.tech.hermes.test.helper.endpoint;

import com.jayway.awaitility.Duration;
import kafka.admin.RackAwareMode;
import kafka.zk.AdminZkClient;
import kafka.zk.KafkaZkClient;
import kafka.zookeeper.ZooKeeperClient;
import org.apache.kafka.common.utils.Time;
import pl.allegro.tech.hermes.api.Topic;
import pl.allegro.tech.hermes.common.config.ConfigFactory;
import pl.allegro.tech.hermes.common.config.Configs;
import pl.allegro.tech.hermes.common.kafka.JsonToAvroMigrationKafkaNamesMapper;
import pl.allegro.tech.hermes.common.kafka.KafkaNamesMapper;
import pl.allegro.tech.hermes.common.kafka.KafkaTopic;

import java.util.Map;
import java.util.Properties;

import static com.jayway.awaitility.Awaitility.waitAtMost;
import static java.util.stream.Collectors.toMap;
import static pl.allegro.tech.hermes.test.helper.builder.TopicBuilder.topic;
import static pl.allegro.tech.hermes.test.helper.endpoint.TimeoutAdjuster.adjust;

/**
 * Created to perform operations directly on broker excluding Hermes internal structures
 */
public class BrokerOperations {

    private static final int DEFAULT_PARTITIONS = 2;
    private static final int DEFAULT_REPLICATION_FACTOR = 1;
    private final static String ZOOKEEPER_METRIC_GROUP = "zookeeper-metrics-group";
    private final static String ZOOKEEPER_METRIC_TYPE = "zookeeper";

    private Map<String, KafkaZkClient> zkClients;
    private KafkaNamesMapper kafkaNamesMapper;

    public BrokerOperations(Map<String, String> kafkaZkConnection, ConfigFactory configFactory) {
        this(kafkaZkConnection, configFactory.getIntProperty(Configs.ZOOKEEPER_SESSION_TIMEOUT),
                configFactory.getIntProperty(Configs.ZOOKEEPER_CONNECTION_TIMEOUT),
                configFactory.getIntProperty(Configs.ZOOKEEPER_MAX_INFLIGHT_REQUESTS),
                configFactory.getStringProperty(Configs.KAFKA_NAMESPACE));
    }

    private BrokerOperations(Map<String, String> kafkaZkConnection, int sessionTimeout, int connectionTimeout,
                             int maxInflightRequests, String namespace) {
        zkClients = kafkaZkConnection.entrySet().stream()
                .collect(toMap(Map.Entry::getKey,
                               e -> {
                                   ZooKeeperClient zooKeeperClient = new ZooKeeperClient(
                                           e.getValue(), connectionTimeout, sessionTimeout, maxInflightRequests,
                                           Time.SYSTEM, ZOOKEEPER_METRIC_GROUP, ZOOKEEPER_METRIC_TYPE);

                                   return new KafkaZkClient(zooKeeperClient, false, Time.SYSTEM);
                               }));
        kafkaNamesMapper = new JsonToAvroMigrationKafkaNamesMapper(namespace);
    }

    public void createTopic(String topicName) {
        zkClients.values().forEach(c -> createTopic(topicName, c));
    }

    public void createTopic(String topicName, String brokerName) {
        createTopic(topicName, zkClients.get(brokerName));
    }

    private void createTopic(String topicName, KafkaZkClient kafkaZkClient) {
        Topic topic = topic(topicName).build();
        kafkaNamesMapper.toKafkaTopics(topic).forEach(kafkaTopic -> {
            AdminZkClient adminZkClient = new AdminZkClient(kafkaZkClient);
            adminZkClient.createTopic(kafkaTopic.name().asString(), DEFAULT_PARTITIONS, DEFAULT_REPLICATION_FACTOR, new Properties(), RackAwareMode.Enforced$.MODULE$);

            waitAtMost(adjust(Duration.ONE_MINUTE)).until(() -> {
                        kafkaZkClient.topicExists(kafkaTopic.name().asString());
                    }
            );
        });
    }

    public boolean topicExists(String topicName, String kafkaClusterName) {
        Topic topic = topic(topicName).build();
        return kafkaNamesMapper.toKafkaTopics(topic)
                .allMatch(kafkaTopic -> zkClients.get(kafkaClusterName).topicExists(kafkaTopic.name().asString()) &&
                        !isMarkedForDeletion(kafkaClusterName, kafkaTopic));
    }

    private boolean isMarkedForDeletion(String kafkaClusterName, KafkaTopic kafkaTopic) {
        return zkClients.get(kafkaClusterName).isTopicMarkedForDeletion(kafkaTopic.name().asString());
    }
}
