// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.yahoo.config.application.api.DeploymentInstanceSpec;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.flags.BooleanFlag;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.hosted.controller.api.application.v4.model.EndpointStatus;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ContainerEndpoint;
import com.yahoo.vespa.hosted.controller.api.integration.dns.Record;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordData;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordName;
import com.yahoo.vespa.hosted.controller.application.Endpoint;
import com.yahoo.vespa.hosted.controller.application.Endpoint.Port;
import com.yahoo.vespa.hosted.controller.application.EndpointId;
import com.yahoo.vespa.hosted.controller.application.EndpointList;
import com.yahoo.vespa.hosted.controller.application.SystemApplication;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.dns.NameServiceQueue.Priority;
import com.yahoo.vespa.hosted.controller.rotation.RotationLock;
import com.yahoo.vespa.hosted.controller.rotation.RotationRepository;
import com.yahoo.vespa.hosted.controller.routing.RoutingId;
import com.yahoo.vespa.hosted.controller.routing.RoutingPolicies;
import com.yahoo.vespa.hosted.rotation.config.RotationsConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The routing controller encapsulates state and methods for inspecting and manipulating deployment endpoints in a
 * hosted Vespa system.
 *
 * The one-stop shop for all your routing needs!
 *
 * @author mpolden
 */
public class RoutingController {

    private final Controller controller;
    private final RoutingPolicies routingPolicies;
    private final RotationRepository rotationRepository;
    private final BooleanFlag hideSharedRoutingEndpoint;

    public RoutingController(Controller controller, RotationsConfig rotationsConfig) {
        this.controller = Objects.requireNonNull(controller, "controller must be non-null");
        this.routingPolicies = new RoutingPolicies(controller);
        this.rotationRepository = new RotationRepository(Objects.requireNonNull(rotationsConfig, "rotationsConfig must be non-null"),
                                                         controller.applications(),
                                                         controller.curator());
        this.hideSharedRoutingEndpoint = Flags.HIDE_SHARED_ROUTING_ENDPOINT.bindTo(controller.flagSource());
    }

    public RoutingPolicies policies() {
        return routingPolicies;
    }

    public RotationRepository rotations() {
        return rotationRepository;
    }

    /** Read and return zone-scoped endpoints for given deployment */
    public EndpointList readEndpointsOf(DeploymentId deployment) {
        Set<Endpoint> endpoints = new LinkedHashSet<>();
        boolean isSystemApplication = SystemApplication.matching(deployment.applicationId()).isPresent();
        // Avoid reading application more than once per call to this
        Supplier<DeploymentSpec> deploymentSpec = Suppliers.memoize(() -> controller.applications().requireApplication(TenantAndApplicationId.from(deployment.applicationId())).deploymentSpec());
        // To discover the cluster name for a zone-scoped endpoint, we need to read routing policies
        for (var policy : routingPolicies.get(deployment).values()) {
            if (!policy.status().isActive()) continue;
            for (var routingMethod :  controller.zoneRegistry().routingMethods(policy.id().zone())) {
                if (routingMethod.isDirect() && !isSystemApplication && !canRouteDirectlyTo(deployment, deploymentSpec.get())) continue;
                endpoints.addAll(policy.zoneEndpointsIn(controller.system(), routingMethod, controller.zoneRegistry()));
                endpoints.add(policy.regionEndpointIn(controller.system(), routingMethod));
            }
        }
        return EndpointList.copyOf(endpoints);
    }

    /** Read application and return declared endpoints for given instance */
    public EndpointList readDeclaredEndpointsOf(ApplicationId instance) {
        if (SystemApplication.matching(instance).isPresent()) return EndpointList.EMPTY;
        return readDeclaredEndpointsOf(TenantAndApplicationId.from(instance)).instance(instance.instance());
    }

    /** Read application and return declared endpoints for given application */
    public EndpointList readDeclaredEndpointsOf(TenantAndApplicationId application) {
        return declaredEndpointsOf(controller.applications().requireApplication(application));
    }

    /** Returns endpoints declared in {@link DeploymentSpec} for given application */
    public EndpointList declaredEndpointsOf(Application application) {
        Set<Endpoint> endpoints = new LinkedHashSet<>();
        for (var instance : application.instances().values()) {
            DeploymentSpec deploymentSpec = application.deploymentSpec();
            Optional<DeploymentInstanceSpec> spec = application.deploymentSpec().instance(instance.name());
            if (spec.isEmpty()) return EndpointList.EMPTY;
            // Add endpoint declared with legacy syntax
            spec.get().globalServiceId().ifPresent(clusterId -> {
                List<DeploymentId> deployments = spec.get().zones().stream()
                                                     .filter(zone -> zone.concerns(Environment.prod))
                                                     .map(zone -> new DeploymentId(instance.id(), ZoneId.from(Environment.prod, zone.region().get())))
                                                     .collect(Collectors.toList());
                RoutingId routingId = RoutingId.of(instance.id(), EndpointId.defaultId());
                endpoints.addAll(computeGlobalEndpoints(routingId, ClusterSpec.Id.from(clusterId), deployments, deploymentSpec));
            });
            // Add endpoints declared with current syntax
            spec.get().endpoints().forEach(declaredEndpoint -> {
                RoutingId routingId = RoutingId.of(instance.id(), EndpointId.of(declaredEndpoint.endpointId()));
                List<DeploymentId> deployments = declaredEndpoint.regions().stream()
                                                                 .map(region -> new DeploymentId(instance.id(),
                                                                                                 ZoneId.from(Environment.prod, region)))
                                                                 .collect(Collectors.toList());
                endpoints.addAll(computeGlobalEndpoints(routingId, ClusterSpec.Id.from(declaredEndpoint.containerId()), deployments, deploymentSpec));
            });
        }
        // Add application endpoints
        for (var declaredEndpoint : application.deploymentSpec().endpoints()) {
            Map<DeploymentId, Integer> deployments = declaredEndpoint.targets().stream()
                                                                     .collect(Collectors.toMap(t -> new DeploymentId(application.id().instance(t.instance()),
                                                                                                                     ZoneId.from(Environment.prod, t.region())),
                                                                                               t -> t.weight()));
            List<RoutingMethod> availableRoutingMethods = routingMethodsOfAll(deployments.keySet(), application.deploymentSpec());
            for (var routingMethod : availableRoutingMethods) {
                endpoints.add(Endpoint.of(application.id())
                                      .targetApplication(EndpointId.of(declaredEndpoint.endpointId()),
                                                         ClusterSpec.Id.from(declaredEndpoint.containerId()),
                                                         deployments)
                                      .routingMethod(routingMethod)
                                      .on(Port.fromRoutingMethod(routingMethod))
                                      .in(controller.system()));
            }
        }
        return EndpointList.copyOf(endpoints);
    }

    /** Read and return zone-scoped endpoints for given deployments, grouped by their zone */
    public Map<ZoneId, List<Endpoint>> readZoneEndpointsOf(Collection<DeploymentId> deployments) {
        var endpoints = new TreeMap<ZoneId, List<Endpoint>>(Comparator.comparing(ZoneId::value));
        for (var deployment : deployments) {
            EndpointList zoneEndpoints = readEndpointsOf(deployment).scope(Endpoint.Scope.zone).not().legacy();
            zoneEndpoints = directEndpoints(zoneEndpoints, deployment.applicationId());
            if  ( ! zoneEndpoints.isEmpty()) {
                endpoints.put(deployment.zoneId(), zoneEndpoints.asList());
            }
        }
        return Collections.unmodifiableMap(endpoints);
    }

    /** Returns certificate DNS names (CN and SAN values) for given deployment */
    public List<String> certificateDnsNames(DeploymentId deployment) {
        List<String> endpointDnsNames = new ArrayList<>();

        // We add first an endpoint name based on a hash of the application ID,
        // as the certificate provider requires the first CN to be < 64 characters long.
        endpointDnsNames.add(commonNameHashOf(deployment.applicationId(), controller.system()));

        // Add wildcard names for global endpoints when deploying to production
        List<Endpoint.EndpointBuilder> builders = new ArrayList<>();
        if (deployment.zoneId().environment().isProduction()) {
            builders.add(Endpoint.of(deployment.applicationId()).target(EndpointId.defaultId()));
            builders.add(Endpoint.of(deployment.applicationId()).wildcard());
        }

        // Add wildcard names for zone endpoints
        builders.add(Endpoint.of(deployment.applicationId()).target(ClusterSpec.Id.from("default"), deployment));
        builders.add(Endpoint.of(deployment.applicationId()).wildcard(deployment));

        // Build all endpoints
        for (var builder : builders) {
            Endpoint endpoint = builder.routingMethod(RoutingMethod.exclusive)
                                       .on(Port.tls())
                                       .in(controller.system());
            endpointDnsNames.add(endpoint.dnsName());
        }
        return Collections.unmodifiableList(endpointDnsNames);
    }

    /** Change status of all global endpoints for given deployment */
    public void setGlobalRotationStatus(DeploymentId deployment, EndpointStatus status) {
        readDeclaredEndpointsOf(deployment.applicationId()).requiresRotation().primary().ifPresent(endpoint -> {
            try {
                controller.serviceRegistry().configServer().setGlobalRotationStatus(deployment, endpoint.upstreamIdOf(deployment), status);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set rotation status of " + endpoint + " in " + deployment, e);
            }
        });
    }

    /** Get global endpoint status for given deployment */
    public Map<Endpoint, EndpointStatus> globalRotationStatus(DeploymentId deployment) {
        var routingEndpoints = new LinkedHashMap<Endpoint, EndpointStatus>();
        readDeclaredEndpointsOf(deployment.applicationId()).requiresRotation().primary().ifPresent(endpoint -> {
            var upstreamName = endpoint.upstreamIdOf(deployment);
            var status = controller.serviceRegistry().configServer().getGlobalRotationStatus(deployment, upstreamName);
            routingEndpoints.put(endpoint, status);
        });
        return Collections.unmodifiableMap(routingEndpoints);
    }

    /**
     * Assigns one or more global rotations to given application, if eligible. The given application is implicitly
     * stored, ensuring that the assigned rotation(s) are persisted when this returns.
     */
    public LockedApplication assignRotations(LockedApplication application, InstanceName instanceName) {
        try (RotationLock rotationLock = rotationRepository.lock()) {
            var rotations = rotationRepository.getOrAssignRotations(application.get().deploymentSpec(),
                                                                    application.get().require(instanceName),
                                                                    rotationLock);
            application = application.with(instanceName, instance -> instance.with(rotations));
            controller.applications().store(application); // store assigned rotation even if deployment fails
        }
        return application;
    }

    /** Returns the global and application-level endpoints for given deployment, as container endpoints */
    public Set<ContainerEndpoint> containerEndpointsOf(Application application, InstanceName instanceName, ZoneId zone) {
        Instance instance = application.require(instanceName);
        boolean registerLegacyNames = requiresLegacyNames(application.deploymentSpec(), instanceName);
        Set<ContainerEndpoint> containerEndpoints = new HashSet<>();
        EndpointList endpoints = declaredEndpointsOf(application);
        EndpointList globalEndpoints = endpoints.scope(Endpoint.Scope.global);
        // Add endpoints backed by a rotation, and register them in DNS if necessary
        for (var assignedRotation : instance.rotations()) {
            var names = new ArrayList<String>();
            EndpointList rotationEndpoints = globalEndpoints.named(assignedRotation.endpointId())
                                                            .requiresRotation();

            // Skip rotations which do not apply to this zone. Legacy names always point to all zones
            if (!registerLegacyNames && !assignedRotation.regions().contains(zone.region())) {
                continue;
            }

            // Omit legacy DNS names when assigning rotations using <endpoints/> syntax
            if (!registerLegacyNames) {
                rotationEndpoints = rotationEndpoints.not().legacy();
            }

            // Register names in DNS
            var rotation = rotationRepository.getRotation(assignedRotation.rotationId());
            if (rotation.isPresent()) {
                rotationEndpoints.forEach(endpoint -> {
                    controller.nameServiceForwarder().createCname(RecordName.from(endpoint.dnsName()),
                                                                  RecordData.fqdn(rotation.get().name()),
                                                                  Priority.normal);
                    names.add(endpoint.dnsName());
                });
            }

            // Include rotation ID as a valid name of this container endpoint (required by global routing health checks)
            names.add(assignedRotation.rotationId().asString());
            containerEndpoints.add(new ContainerEndpoint(assignedRotation.clusterId().value(),
                                                         asString(Endpoint.Scope.global),
                                                         names));
        }
        // Add endpoints not backed by a rotation (i.e. other routing methods so that the config server always knows
        // about global names, even when not using rotations)
        DeploymentId deployment = new DeploymentId(instance.id(), zone);
        globalEndpoints.not().requiresRotation()
                       .targets(deployment)
                       .groupingBy(Endpoint::cluster)
                       .forEach((clusterId, clusterEndpoints) -> {
                           containerEndpoints.add(new ContainerEndpoint(clusterId.value(),
                                                                        asString(Endpoint.Scope.global),
                                                                        clusterEndpoints.mapToList(Endpoint::dnsName)));
                       });
        return Collections.unmodifiableSet(containerEndpoints);
    }

    /** Remove endpoints in DNS for all rotations assigned to given instance */
    public void removeEndpointsInDns(Application application, InstanceName instanceName) {
        Set<Endpoint> endpointsToRemove = new LinkedHashSet<>();
        Instance instance = application.require(instanceName);
        // Compute endpoints from rotations. When removing DNS records for rotation-based endpoints we cannot use the
        // deployment spec, because submitting an empty deployment spec is the first step of removing an application
        for (var rotation : instance.rotations()) {
            var deployments = rotation.regions().stream()
                                      .map(region -> new DeploymentId(instance.id(), ZoneId.from(Environment.prod, region)))
                                      .collect(Collectors.toList());
            endpointsToRemove.addAll(computeGlobalEndpoints(RoutingId.of(instance.id(), rotation.endpointId()),
                                                            rotation.clusterId(), deployments, application.deploymentSpec()));
        }
        endpointsToRemove.forEach(endpoint -> controller.nameServiceForwarder()
                                                        .removeRecords(Record.Type.CNAME,
                                                                       RecordName.from(endpoint.dnsName()),
                                                                       Priority.normal));
    }

    /** Returns the routing methods that are available across all given deployments */
    private List<RoutingMethod> routingMethodsOfAll(Collection<DeploymentId> deployments, DeploymentSpec deploymentSpec) {
        var deploymentsByMethod = new HashMap<RoutingMethod, Set<DeploymentId>>();
        for (var deployment : deployments) {
            for (var method : controller.zoneRegistry().routingMethods(deployment.zoneId())) {
                deploymentsByMethod.putIfAbsent(method, new LinkedHashSet<>());
                deploymentsByMethod.get(method).add(deployment);
            }
        }
        var routingMethods = new ArrayList<RoutingMethod>();
        deploymentsByMethod.forEach((method, supportedDeployments) -> {
            if (supportedDeployments.containsAll(deployments)) {
                if (method.isDirect() && !canRouteDirectlyTo(deployments, deploymentSpec)) return;
                routingMethods.add(method);
            }
        });
        return Collections.unmodifiableList(routingMethods);
    }

    /** Returns whether traffic can be directly routed to all given deployments */
    private boolean canRouteDirectlyTo(Collection<DeploymentId> deployments, DeploymentSpec deploymentSpec) {
        return deployments.stream().allMatch(deployment -> canRouteDirectlyTo(deployment, deploymentSpec));
    }

    /** Returns whether traffic can be directly routed to given deployment */
    private boolean canRouteDirectlyTo(DeploymentId deploymentId, DeploymentSpec deploymentSpec) {
        if (controller.system().isPublic()) return true; // Public always supports direct routing
        if (controller.system().isCd()) return true; // CD deploys directly so we cannot enforce all requirements below
        if (deploymentId.zoneId().environment().isManuallyDeployed()) return true; // Manually deployed zones always support direct routing

        // Check Athenz service presence. The test framework uses this identity when sending requests to the
        // deployment's container(s).
        var athenzService = deploymentSpec.instance(deploymentId.applicationId().instance())
                                          .flatMap(instance -> instance.athenzService(deploymentId.zoneId().environment(),
                                                                                      deploymentId.zoneId().region()));
        if (athenzService.isEmpty()) return false;
        return true;
    }

    /** Compute global endpoints for given routing ID, application and deployments */
    private List<Endpoint> computeGlobalEndpoints(RoutingId routingId, ClusterSpec.Id cluster, List<DeploymentId> deployments, DeploymentSpec deploymentSpec) {
        var endpoints = new ArrayList<Endpoint>();
        var directMethods = 0;
        var availableRoutingMethods = routingMethodsOfAll(deployments, deploymentSpec);
        boolean legacyNamesAvailable = requiresLegacyNames(deploymentSpec, routingId.instance().instance());

        for (var method : availableRoutingMethods) {
            if (method.isDirect() && ++directMethods > 1) {
                throw new IllegalArgumentException("Invalid routing methods for " + routingId + ": Exceeded maximum " +
                                                   "direct methods");
            }
            endpoints.add(Endpoint.of(routingId.instance())
                                  .target(routingId.endpointId(), cluster, deployments)
                                  .on(Port.fromRoutingMethod(method))
                                  .routingMethod(method)
                                  .in(controller.system()));
            // Add legacy endpoints
            if (legacyNamesAvailable && method == RoutingMethod.shared) {
                endpoints.add(Endpoint.of(routingId.instance())
                                      .target(routingId.endpointId(), cluster, deployments)
                                      .on(Port.plain(4080))
                                      .legacy()
                                      .routingMethod(method)
                                      .in(controller.system()));
                endpoints.add(Endpoint.of(routingId.instance())
                                      .target(routingId.endpointId(), cluster, deployments)
                                      .on(Port.tls(4443))
                                      .legacy()
                                      .routingMethod(method)
                                      .in(controller.system()));
            }
        }
        return endpoints;
    }

    /** Whether legacy global DNS names should be available for given application */
    private static boolean requiresLegacyNames(DeploymentSpec deploymentSpec, InstanceName instanceName) {
        return deploymentSpec.instance(instanceName)
                             .flatMap(DeploymentInstanceSpec::globalServiceId)
                             .isPresent();
    }

    /** Create a common name based on a hash of given application. This must be less than 64 characters long. */
    private String commonNameHashOf(ApplicationId application, SystemName system) {
        HashCode sha1 = Hashing.sha1().hashString(application.serializedForm(), StandardCharsets.UTF_8);
        String base32 = BaseEncoding.base32().omitPadding().lowerCase().encode(sha1.asBytes());
        return 'v' + base32 + Endpoint.internalDnsSuffix(system);
    }

    /** Returns direct routing endpoints if any exist and feature flag is set for given application */
    // TODO: Remove this when feature flag is removed, and in-line .direct() filter where relevant
    public EndpointList directEndpoints(EndpointList endpoints, ApplicationId application) {
        boolean hideSharedEndpoint = hideSharedRoutingEndpoint.with(FetchVector.Dimension.APPLICATION_ID, application.serializedForm()).value();
        EndpointList directEndpoints = endpoints.direct();
        if (hideSharedEndpoint && !directEndpoints.isEmpty()) {
            return directEndpoints;
        }
        return endpoints;
    }

    private static String asString(Endpoint.Scope scope) {
        switch (scope) {
            case application: return "application";
            case global: return "global";
            case weighted: return "weighted";
            case zone: return "zone";
        }
        throw new IllegalArgumentException("Unknown scope " + scope);
    }

}
