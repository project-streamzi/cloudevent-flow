package io.streamzi.openshift.dataflow.deployment;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.streamzi.openshift.dataflow.FlowUtil;

import io.streamzi.openshift.dataflow.model.ProcessorConstants;
import io.streamzi.openshift.dataflow.model.ProcessorFlow;
import io.streamzi.openshift.dataflow.model.ProcessorInputPort;
import io.streamzi.openshift.dataflow.model.ProcessorLink;
import io.streamzi.openshift.dataflow.model.ProcessorNode;
import io.streamzi.openshift.dataflow.model.ProcessorOutputPort;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopicBuilder;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;

public class TargetState {

    private static final Logger logger = Logger.getLogger(TargetState.class.getName());

    private String cloudName;

    private ProcessorFlow flow;

    private Map<String, String> bootstrapServerCache;

    private Set<DeploymentConfig> deploymentConfigs = new HashSet<>();

    private Set<CustomResource> topicCrds = new HashSet<>();

    private Set<ConfigMap> evConfigMaps = new HashSet<>();

    public TargetState(String cloudName, ProcessorFlow flow, Map<String, String> bootstrapServerCache) {
        this.cloudName = cloudName;
        this.flow = flow;
        this.bootstrapServerCache = bootstrapServerCache;
    }

    public String getCloudName() {
        return cloudName;
    }

    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    public Set<DeploymentConfig> getDeploymentConfigs() {
        return deploymentConfigs;
    }

    public void setDeploymentConfigs(Set<DeploymentConfig> deploymentConfigs) {
        this.deploymentConfigs = deploymentConfigs;
    }

    public Set<CustomResource> getTopicCrds() {
        return topicCrds;
    }

    public void setTopicCrds(Set<CustomResource> topicCrds) {
        this.topicCrds = topicCrds;
    }

    public Set<ConfigMap> getEvConfigMaps() {
        return evConfigMaps;
    }

    public void setEvConfigMaps(Set<ConfigMap> evConfigMaps) {
        this.evConfigMaps = evConfigMaps;
    }

    public boolean add(DeploymentConfig deploymentConfig) {
        return deploymentConfigs.add(deploymentConfig);
    }

    public boolean add(CustomResource customResource) {
        return topicCrds.add(customResource);
    }

    public Set<String> getDeploymentConfigNames() {
        //remove DCs that are no longer required.
        return deploymentConfigs.stream()
                .map(dc -> dc.getMetadata().getName())
                .collect(Collectors.toSet());
    }

    public Set<String> getConfigMapNames() {
        Set<String> topics =
                topicCrds.stream()
                        .map(cm -> cm.getMetadata().getName())
                        .collect(Collectors.toSet());


        Set<String> evs = evConfigMaps.stream()
                .map(cm -> cm.getMetadata().getName())
                .collect(Collectors.toSet());
        evs.addAll(topics);

        return evs;
    }

    public void build() {

        deploymentConfigs.addAll(flow.getNodes().stream()
                .filter(node -> node.getProcessorType().equals(ProcessorConstants.ProcessorType.DEPLOYABLE_IMAGE))
                .filter(node -> node.getTargetClouds().keySet().contains(cloudName) && node.getTargetClouds().get(cloudName) > 0)
                .map(this::buildDeploymentConfigs)
                .collect(Collectors.toSet()));

        evConfigMaps.addAll(
                flow.getNodes().stream()
                        .filter(node -> node.getProcessorType().equals(ProcessorConstants.ProcessorType.DEPLOYABLE_IMAGE))
                        .filter(node -> node.getTargetClouds().keySet().contains(cloudName) && node.getTargetClouds().get(cloudName) > 0)
                        .map(this::buildEnvVarConfigMaps)
                        .collect(Collectors.toSet()));


        topicCrds.addAll(flow.getLinks().stream()
                .filter(link -> link.getSource().getParent().getProcessorType().equals(ProcessorConstants.ProcessorType.DEPLOYABLE_IMAGE))
                .filter(link -> calculateTopicHost(link.getSource().getParent()).equals(cloudName))
                .map(this::buildTopicConfigMaps)
                .collect(Collectors.toSet()));

    }

    private DeploymentConfig buildDeploymentConfigs(ProcessorNode node) {

        // Only create deployments for deployable image nodes
        final String dcName = node.getParent().getName() + "-" + FlowUtil.sanitisePodName(node.getDisplayName()) + "-" + node.getUuid().substring(0, 6);

        //Get the envVars for the node
        Map<String, String> envVarMap = getNodeEnvVars(node);

        List<EnvVar> envVar = envVarMap.keySet().stream()
                .map(key -> new EnvVar(key, envVarMap.get(key), null)
                ).collect(Collectors.toList());

        final Container container = new ContainerBuilder()
                .withName(node.getParent().getName())
                .withEnv(envVar)
                .withImage("docker.io/" + node.getImageName())
                .withImagePullPolicy("IfNotPresent")
                .build();

        return new io.fabric8.openshift.api.model.DeploymentConfigBuilder()
                .withNewMetadata()
                .withName(dcName)
                .addToLabels("app", node.getParent().getName())
                .addToLabels("streamzi/type", "processor-flow")
                .endMetadata()
                .withNewSpec()
                .withReplicas(node.getTargetClouds().get(cloudName))
                .addNewTrigger()
                .withType("ConfigChange")
                .endTrigger()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", node.getParent().getName())
                .endMetadata()
                .withNewSpec()
                .addNewContainerLike(container)
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

    }

    private Map<String, String> getNodeEnvVars(ProcessorNode node) {

        Map<String, String> envVars = new HashMap<>();

        envVars.put(FlowUtil.sanitiseEnvVar(ProcessorConstants.NODE_UUID), node.getUuid());

        // Add environment variables for node settingds
        for (String key : node.getSettings().keySet()) {
            envVars.put(FlowUtil.sanitiseEnvVar(key), node.getSettings().get(key));
        }

        // Add environment variables for the flow global settings
        for (String key : node.getParent().getGlobalSettings().keySet()) {
            envVars.put(FlowUtil.sanitiseEnvVar(key), node.getParent().getGlobalSettings().get(key));
        }


        //Input topics
        for (ProcessorInputPort input : node.getInputs().values()) {
            for (ProcessorLink link : input.getLinks()) {
                ProcessorNode sourceNode = link.getSource().getParent();
                String bootstrapServers = bootstrapServerCache.get(calculateTopicHost(sourceNode));
                if (sourceNode.getProcessorType() == ProcessorConstants.ProcessorType.TOPIC_ENDPOINT) {
                    // This is a pre-existing topic
                    envVars.put(FlowUtil.sanitiseEnvVar(input.getName()), link.getSource().getName());
                } else {
                    // This topic will be created
                    envVars.put(FlowUtil.sanitiseEnvVar(input.getName() + "_BOOTSTRAP_SERVERS"), bootstrapServers);
                    String topicName = node.getParent().getName() + "-" + link.getSource().getParent().getUuid() + "-" + link.getSource().getName();
                    envVars.put(FlowUtil.sanitiseEnvVar(input.getName()), topicName);
                }
            }
        }

        //Output topics
        for (ProcessorOutputPort output : node.getOutputs().values()) {

            String bootstrapServers = bootstrapServerCache.get(calculateTopicHost(node));
            envVars.put(FlowUtil.sanitiseEnvVar(output.getName() + "_BOOTSTRAP_SERVERS"), bootstrapServers);

            String topicName = node.getParent().getName() + "-" + node.getUuid() + "-" + output.getName();
            envVars.put(FlowUtil.sanitiseEnvVar(output.getName()), topicName);
        }

        return envVars;
    }

    private ConfigMap buildEnvVarConfigMaps(ProcessorNode node) {

        final String dcName = flow.getName() + "-" + FlowUtil.sanitisePodName(node.getDisplayName()) + "-" + node.getUuid().substring(0, 6);
        final String cmName = dcName + "-ev.cm";

        final Map<String, String> labels = new HashMap<>();
        labels.put("streamzi.io/kind", "ev");
        labels.put("streamzi.io/target", dcName);
        labels.put("app", flow.getName());

        final ObjectMeta om = new ObjectMeta();
        om.setName(cmName);
        om.setLabels(labels);

        final ConfigMap cm = new ConfigMap();
        cm.setMetadata(om);

        Map<String, String> evs = getNodeEnvVars(node);

        cm.setData(evs);
        return cm;

    }

    private CustomResource buildTopicConfigMaps(ProcessorLink link) {

        final String topicName = flow.getName() + "-" + link.getSource().getParent().getUuid() + "-" + link.getSource().getName();

        final Map<String, String> labels = new HashMap<>();
        labels.put("strimzi.io/cluster", "my-cluster");
        labels.put("strimzi.io/kind", "topic");
        labels.put("streamzi.io/source", "autocreated");
        labels.put("app", flow.getName());

        final KafkaTopic topic = new KafkaTopicBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(topicName).withLabels(labels).build())
                .withNewSpec()
                    .withReplicas(1)
                    .withPartitions(20)
                    .withConfig(emptyMap())
                .endSpec()
                .build();

        return topic;
    }


    private String calculateTopicHost(ProcessorNode node) {
        if (node.getOutputCloud() != null && !node.getOutputCloud().isEmpty()) {
            return node.getOutputCloud();
        }

        Optional<String> optCloud = node.getTargetClouds().keySet().stream().max(Comparator.comparingInt(key -> node.getTargetClouds().get(key)));
        return optCloud.orElse("UNKNONW");
    }

}
