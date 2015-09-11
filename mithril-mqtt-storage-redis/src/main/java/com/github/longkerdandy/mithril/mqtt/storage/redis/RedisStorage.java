package com.github.longkerdandy.mithril.mqtt.storage.redis;

import com.github.longkerdandy.mithril.mqtt.util.Topics;
import com.lambdaworks.redis.*;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import com.lambdaworks.redis.api.async.RedisAsyncCommands;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.mqtt.*;
import org.apache.commons.lang3.BooleanUtils;

import java.io.UnsupportedEncodingException;
import java.util.*;

import static com.github.longkerdandy.mithril.mqtt.util.Topics.END;
import static io.netty.buffer.Unpooled.wrappedBuffer;

/**
 * Redis Storage
 */
public class RedisStorage {

    // A scalable thread-safe Redis client. Multiple threads may share one connection if they avoid
    // blocking and transactional operations such as BLPOP and MULTI/EXEC.
    protected RedisClient client;
    // A thread-safe connection to a redis server. Multiple threads may share one StatefulRedisConnection
    protected StatefulRedisConnection<String, String> conn;

    public RedisStorage(String host, int port) {
        this.client = new RedisClient(host, port);
    }

    /**
     * Convert Map to MqttMessage
     *
     * @param map Map
     * @return MqttMessage
     */
    public static MqttMessage mapToMqtt(Map<String, String> map) {
        int type = Integer.parseInt(map.get("type"));
        if (type == MqttMessageType.PUBLISH.value()) {
            byte[] payload = null;
            if (map.get("payload") != null) try {
                payload = map.get("payload").getBytes("ISO-8859-1");
            } catch (UnsupportedEncodingException ignore) {
            }
            return MqttMessageFactory.newMessage(
                    new MqttFixedHeader(
                            MqttMessageType.PUBLISH,
                            BooleanUtils.toBoolean(map.getOrDefault("dup", "0"), "1", "0"),
                            MqttQoS.valueOf(Integer.parseInt(map.getOrDefault("qos", "0"))),
                            BooleanUtils.toBoolean(map.getOrDefault("retain", "0"), "1", "0"),
                            0
                    ),
                    new MqttPublishVariableHeader(map.get("topicName"), Integer.parseInt(map.getOrDefault("packetId", "0"))),
                    payload == null ? null : wrappedBuffer(payload)
            );
        } else if (type == MqttMessageType.PUBREL.value()) {
            return MqttMessageFactory.newMessage(
                    new MqttFixedHeader(
                            MqttMessageType.PUBREL,
                            false,
                            MqttQoS.AT_LEAST_ONCE,
                            false,
                            0
                    ),
                    MqttMessageIdVariableHeader.from(Integer.parseInt(map.getOrDefault("packetId", "0"))),
                    null
            );
        } else {
            throw new IllegalArgumentException("Invalid in-flight MQTT message type: " + MqttMessageType.valueOf(type));
        }
    }

    /**
     * Convert MqttMessage to Map
     *
     * @param msg MqttMessage
     * @return Map
     */
    public static Map<String, String> mqttToMap(MqttMessage msg) {
        if (msg.fixedHeader().messageType() == MqttMessageType.PUBLISH) {
            Map<String, String> map = new HashMap<>();
            map.put("type", String.valueOf(MqttMessageType.PUBLISH.value()));
            map.put("retain", BooleanUtils.toString(msg.fixedHeader().isRetain(), "1", "0"));
            map.put("qos", String.valueOf(msg.fixedHeader().qosLevel().value()));
            map.put("dup", BooleanUtils.toString(msg.fixedHeader().isDup(), "1", "0"));
            map.put("topicName", ((MqttPublishVariableHeader) msg.variableHeader()).topicName());
            map.put("packetId", String.valueOf(((MqttPublishVariableHeader) msg.variableHeader()).messageId()));
            if (msg.payload() != null) try {
                map.put("payload", new String(((ByteBuf) msg.payload()).array(), "ISO-8859-1"));
            } catch (UnsupportedEncodingException ignore) {
            }
            return map;
        } else if (msg.fixedHeader().messageType() == MqttMessageType.PUBREL) {
            Map<String, String> map = new HashMap<>();
            map.put("type", String.valueOf(MqttMessageType.PUBREL.value()));
            map.put("qos", "1");
            map.put("packetId", String.valueOf(((MqttPublishVariableHeader) msg.variableHeader()).messageId()));
            return map;
        } else {
            throw new IllegalArgumentException("Invalid in-flight MQTT message type: " + msg.fixedHeader().messageType());
        }
    }

    public void init() {
        // open a new connection to a Redis server that treats keys and values as UTF-8 strings
        this.conn = this.client.connect();
    }

    public void destroy() {
        // shutdown this client and close all open connections
        this.client.shutdown();
    }

    /**
     * Remove multiples keys
     * USE WITH CAUTION
     *
     * @param keys Keys
     * @return The number of keys have been removed
     */
    public RedisFuture<Long> removeKeys(String[] keys) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.del(keys);
    }

    /**
     * Iteration connected clients for the mqtt server node
     *
     * @param node   MQTT Server Node
     * @param cursor Scan Cursor
     * @param count  Limit
     * @return Clients and Cursor
     */
    public RedisFuture<ValueScanCursor<String>> getConnectedClients(String node, String cursor, long count) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.sscan(RedisKey.connectedClients(node), ScanCursor.of(cursor), ScanArgs.Builder.limit(count));
    }

    /**
     * Get connected mqtt server nodes for the client
     * Client may have more than one connected nodes because:
     * Slow detection of tcp disconnected event
     * Clients use the the same client id
     *
     * @param clientId Client Id
     * @return MQTT Server Nodes
     */
    public RedisFuture<Set<String>> getConnectedNodes(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.smembers(RedisKey.connectedNodes(clientId));
    }

    /**
     * Add connected mqtt server node for the client
     *
     * @param clientId Client Id
     * @param node     MQTT Server Node
     * @return RedisFutures (add to server's clients, add to client's servers)
     */
    public List<RedisFuture> updateConnectedNodes(String clientId, String node) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.sadd(RedisKey.connectedClients(node), clientId));
        list.add(commands.sadd(RedisKey.connectedNodes(clientId), node));
        return list;
    }

    /**
     * Remove connected mqtt server node for the client
     *
     * @param clientId Client Id
     * @param node     MQTT Server Node
     * @return RedisFutures (remove from server's clients, remove from client's servers)
     */
    public List<RedisFuture> removeConnectedNodes(String clientId, String node) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.srem(RedisKey.connectedClients(node), clientId));
        list.add(commands.srem(RedisKey.connectedNodes(clientId), node));
        return list;
    }

    /**
     * Is client (session) exist?
     *
     * @param clientId Client Id
     * @return 1 if exist
     */
    public RedisFuture<Long> isClientExist(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.exists(new String[]{RedisKey.clientExist(clientId)});
    }

    /**
     * Mark client (session) exist
     *
     * @param clientId Client Id
     * @return OK if was executed correctly
     */
    public RedisFuture<String> updateClientExist(String clientId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.set(RedisKey.clientExist(clientId), "1");
    }

    /**
     * Get all in-flight message's packet ids for the client
     * We separate packet ids with different clean session
     * Including:
     * QoS 1 and QoS 2 PUBLISH messages which have been sent to the Client, but have not been acknowledged.
     * QoS 0, QoS 1 and QoS 2 PUBLISH messages pending transmission to the Client.
     * QoS 2 PUBREL messages which have been sent from the Client, but have not been acknowledged.
     *
     * @param clientId     Client Id
     * @param cleanSession Clean Session
     * @return In-flight message's Packet Ids
     */
    public RedisFuture<List<String>> getInFlightIds(String clientId, boolean cleanSession) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.lrange(RedisKey.inFlightList(clientId, cleanSession), 0, -1);
    }

    /**
     * Get specific in-flight message for the client
     *
     * @param clientId Client Id
     * @param packetId Packet Id
     * @return In-flight message in Map format
     */
    public RedisFuture<Map<String, String>> getInFlightMessage(String clientId, int packetId) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.hgetall(RedisKey.inFlightMessage(clientId, packetId));
    }

    /**
     * Remove specific in-flight message for the client
     * We separate packet ids with different clean session
     *
     * @param clientId     Client Id
     * @param cleanSession Clean Session
     * @param packetId     Packet Id
     * @return RedisFutures (Remove from the List, remove the Hash)
     */
    public List<RedisFuture> removeInFlightMessage(String clientId, boolean cleanSession, int packetId) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.lrem(RedisKey.inFlightList(clientId, cleanSession), 0, String.valueOf(packetId)));
        list.add(commands.del(RedisKey.inFlightMessage(clientId, packetId)));
        return list;
    }

    /**
     * Get the topic's subscriptions
     * Include both clean session's subscriptions
     * Topic Levels must be sanitized using Topics
     *
     * @param topicLevels List of topic levels
     * @return Subscriptions
     */
    public RedisFuture<Map<String, String>> getTopicSubscriptions(List<String> topicLevels) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        if (Topics.isTopicFilter(topicLevels)) {
            return commands.hgetall(RedisKey.topicFilter(topicLevels));
        } else {
            return commands.hgetall(RedisKey.topicName(topicLevels));
        }
    }

    /**
     * Get the client's subscriptions
     * We separate subscriptions with different clean session
     *
     * @param clientId     Client Id
     * @param cleanSession Clean Session
     * @return Subscriptions
     */
    public RedisFuture<Map<String, String>> getClientSubscriptions(String clientId, boolean cleanSession) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        return commands.hgetall(RedisKey.subscription(clientId, cleanSession));
    }

    /**
     * Update topic name subscription for the client
     * We separate subscriptions with different clean session
     * Topic Levels must be sanitized using Topics
     *
     * @param clientId     Client Id
     * @param cleanSession Clean Session
     * @param topicLevels  List of topic levels
     * @param qos          Subscription QoS
     * @return RedisFutures (add to client's subscriptions, add to topic's subscriptions, add to topic filter tree)
     */
    public List<RedisFuture> updateSubscription(String clientId, boolean cleanSession, List<String> topicLevels, String qos) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.hset(RedisKey.subscription(clientId, cleanSession), String.join("/", topicLevels), qos));
        if (Topics.isTopicFilter(topicLevels)) {
            list.add(commands.hset(RedisKey.topicFilter(topicLevels), clientId, qos));
            for (int i = 0; i < topicLevels.size(); i++) {
                list.add(commands.hincrby(RedisKey.topicFilterChild(topicLevels.subList(0, i)), topicLevels.get(i), 1));
            }
        } else {
            list.add(commands.hset(RedisKey.topicName(topicLevels), clientId, qos));
        }
        return list;
    }

    /***
     * Remove topic name subscription for the client
     * We separate subscriptions with different clean session
     * Topic Levels must be sanitized using Topics
     *
     * @param clientId     Client Id
     * @param cleanSession Clean Session
     * @param topicLevels  List of topic levels
     * @return RedisFutures (remove from client's subscriptions, remove from topic's subscriptions, remove from topic filter tree)
     */
    public List<RedisFuture> removeSubscription(String clientId, boolean cleanSession, List<String> topicLevels) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        list.add(commands.hdel(RedisKey.subscription(clientId, cleanSession), String.join("/", topicLevels)));
        if (Topics.isTopicFilter(topicLevels)) {
            list.add(commands.hdel(RedisKey.topicFilter(topicLevels), clientId));
            for (int i = 0; i < topicLevels.size(); i++) {
                list.add(commands.hincrby(RedisKey.topicFilterChild(topicLevels.subList(0, i)), topicLevels.get(i), -1));
            }
        } else {
            list.add(commands.hdel(RedisKey.topicName(topicLevels), clientId));
        }
        return list;
    }

    /**
     * Remove all subscriptions for the client
     * We separate subscriptions with different clean session
     *
     * @param clientId     Client Id
     * @param cleanSession Clean Session
     * @return RedisFutures (remove from topic's subscriptions, remove from topic filter tree, remove from client's subscriptions)
     */
    public List<RedisFuture> removeAllSubscriptions(String clientId, boolean cleanSession) {
        List<RedisFuture> list = new ArrayList<>();
        RedisAsyncCommands<String, String> commands = this.conn.async();
        commands.hgetall(RedisKey.subscription(clientId, cleanSession)).thenAccept(map -> {
            map.forEach((k, v) -> {
                if (Topics.isTopicFilter(k)) {
                    list.add(commands.hdel(RedisKey.topicFilter(k), clientId));
                    List<String> levels = Topics.sanitizeTopicFilter(k);
                    for (int i = 0; i < levels.size(); i++) {
                        list.add(commands.hincrby(RedisKey.topicFilterChild(levels.subList(0, i)), levels.get(i), -1));
                    }
                } else {
                    list.add(commands.hdel(RedisKey.topicName(k), clientId));
                }
            });
            list.add(commands.del(RedisKey.subscription(clientId, cleanSession)));
        });
        return list;
    }

    /**
     * Find the matching children from the topic filter tree
     * Topic Levels must be sanitized using Topics
     *
     * @param topicLevels List of topic levels
     * @param index       Current match level
     * @return Possible matching children
     */
    public RedisFuture<List<String>> matchTopicFilterLevel(List<String> topicLevels, int index) {
        RedisAsyncCommands<String, String> commands = this.conn.async();
        if (index == topicLevels.size() - 1) {
            return commands.hmget(RedisKey.topicFilterChild(topicLevels.subList(0, index)), END, "#");
        } else {
            return commands.hmget(RedisKey.topicFilterChild(topicLevels.subList(0, index)), topicLevels.get(index), "#", "+");
        }
    }
}
