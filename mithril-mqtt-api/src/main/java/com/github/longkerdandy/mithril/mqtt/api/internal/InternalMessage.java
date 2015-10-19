package com.github.longkerdandy.mithril.mqtt.api.internal;

import io.netty.handler.codec.mqtt.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Represent MQTT Message passed in Communicator
 */
@SuppressWarnings("unused")
public class InternalMessage<T> {

    // fixed header
    private MqttMessageType messageType;
    private boolean dup;
    private MqttQoS qos;
    private boolean retain;

    // some info in CONNECT message, but useful for stateless transfer
    private MqttVersion version;
    private boolean cleanSession;
    private String clientId;
    private String userName;

    // broker id, when this message is sent by broker
    private String brokerId;

    // variable header and payload
    private T payload;

    protected InternalMessage() {
    }

    public InternalMessage(MqttMessageType messageType, boolean dup, MqttQoS qos, boolean retain,
                           MqttVersion version, boolean cleanSession, String clientId, String userName,
                           String brokerId) {
        this(messageType, dup, qos, retain, version, cleanSession, clientId, userName, brokerId, null);
    }

    public InternalMessage(MqttMessageType messageType, boolean dup, MqttQoS qos, boolean retain,
                           MqttVersion version, boolean cleanSession, String clientId, String userName,
                           String brokerId, T payload) {
        this.messageType = messageType;
        this.dup = dup;
        this.qos = qos;
        this.retain = retain;
        this.version = version;
        this.cleanSession = cleanSession;
        this.clientId = clientId;
        this.userName = userName;
        this.brokerId = brokerId;
        this.payload = payload;
    }

    protected static <T> InternalMessage<T> fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                            String brokerId,
                                                            MqttFixedHeader fixedHeader) {
        return new InternalMessage<>(fixedHeader.messageType(), fixedHeader.dup(), fixedHeader.qos(), fixedHeader.retain(),
                version, cleanSession, clientId, userName, brokerId);
    }

    public static InternalMessage<Connect> fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                           String brokerId,
                                                           MqttConnectMessage mqtt) {
        InternalMessage<Connect> msg = fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
        // TODO: Deal with Will Message after Netty fixed the field type
        msg.payload = new Connect(mqtt.variableHeader().willRetain(), mqtt.variableHeader().willQos(), mqtt.payload().willTopic(), null);
        return msg;
    }

    public static InternalMessage<ConnAck> fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                           String brokerId,
                                                           MqttConnAckMessage mqtt) {
        InternalMessage<ConnAck> msg = fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
        msg.payload = new ConnAck(mqtt.variableHeader().returnCode(), mqtt.variableHeader().sessionPresent());
        return msg;
    }

    public static InternalMessage<Subscribe> fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                             String brokerId,
                                                             MqttSubscribeMessage mqtt, List<MqttSubAckReturnCode> returnCodes) {
        InternalMessage<Subscribe> msg = fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
        // forge topic subscriptions
        if (mqtt.payload().subscriptions().size() != returnCodes.size()) {
            throw new IllegalArgumentException("MQTT SUBSCRIBE message's subscriptions count not equal to granted QoS count");
        }
        List<TopicSubscription> topicSubscriptions = new ArrayList<>();
        for (int i = 0; i < mqtt.payload().subscriptions().size(); i++) {
            TopicSubscription subscription = new TopicSubscription(mqtt.payload().subscriptions().get(i).topic(), returnCodes.get(i));
            topicSubscriptions.add(subscription);
        }
        msg.payload = new Subscribe(mqtt.variableHeader().packetId(), topicSubscriptions);
        return msg;
    }

    public static InternalMessage<SubAck> fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                          String brokerId,
                                                          MqttSubAckMessage mqtt) {
        InternalMessage<SubAck> msg = fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
        msg.payload = new SubAck(mqtt.variableHeader().packetId(), mqtt.payload().grantedQoSLevels());
        return msg;
    }

    public static InternalMessage<Unsubscribe> fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                               String brokerId,
                                                               MqttUnsubscribeMessage mqtt) {
        InternalMessage<Unsubscribe> msg = fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
        msg.payload = new Unsubscribe(mqtt.variableHeader().packetId(), mqtt.payload().topics());
        return msg;
    }

    public static InternalMessage<Publish> fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                           String brokerId,
                                                           MqttPublishMessage mqtt) {
        InternalMessage<Publish> msg = fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
        // forge bytes payload
        byte[] bytes = new byte[mqtt.payload().readableBytes()];
        mqtt.payload().readBytes(bytes);
        msg.payload = new Publish(mqtt.variableHeader().topicName(), mqtt.variableHeader().packetId(), bytes);
        return msg;
    }

    public static InternalMessage<Disconnect> fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                              String brokerId,
                                                              MqttMessage mqtt, boolean clean) {
        InternalMessage<Disconnect> msg = fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
        msg.payload = new Disconnect(clean);
        return msg;
    }

    public static InternalMessage fromMqttMessage(MqttVersion version, boolean cleanSession, String clientId, String userName,
                                                  String brokerId,
                                                  MqttMessage mqtt) {
        if (mqtt.variableHeader() != null && mqtt.variableHeader() instanceof MqttPacketIdVariableHeader) {
            InternalMessage<PacketId> msg = fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
            msg.payload = new PacketId(((MqttPacketIdVariableHeader) mqtt.variableHeader()).packetId());
            return msg;
        } else {
            return fromMqttMessage(version, cleanSession, clientId, userName, brokerId, mqtt.fixedHeader());
        }
    }

    public MqttMessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MqttMessageType messageType) {
        this.messageType = messageType;
    }

    public boolean isDup() {
        return dup;
    }

    public void setDup(boolean dup) {
        this.dup = dup;
    }

    public MqttQoS getQos() {
        return qos;
    }

    public void setQos(MqttQoS qos) {
        this.qos = qos;
    }

    public boolean isRetain() {
        return retain;
    }

    public void setRetain(boolean retain) {
        this.retain = retain;
    }

    public MqttVersion getVersion() {
        return version;
    }

    public void setVersion(MqttVersion version) {
        this.version = version;
    }

    public boolean isCleanSession() {
        return cleanSession;
    }

    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getBrokerId() {
        return brokerId;
    }

    public void setBrokerId(String brokerId) {
        this.brokerId = brokerId;
    }

    public T getPayload() {
        return payload;
    }

    public void setPayload(T payload) {
        this.payload = payload;
    }
}
