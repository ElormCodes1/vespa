// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance.servicedump;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.reports.BaseReport;

/**
 * JSON representation of Vespa service dump report.
 *
 * @author bjorncs
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
class ServiceDumpReport extends BaseReport {

    public static final String REPORT_ID = "serviceDump";

    private static final String REQUESTED_AT_FIELD = "requestedAt";
    private static final String STARTED_AT_FIELD = "startedAt";
    private static final String COMPLETED_AT_FIELD = "completedAt";
    private static final String FAILED_AT_FIELD = "failedAt";
    private static final String LOCATION_FIELD = "location";
    private static final String CONFIG_ID_FIELD = "configId";
    private static final String EXPIRY_FIELD = "expiry";
    private static final String ERROR_FIELD = "error";

    private final Long requestedAt;
    private final Long startedAt;
    private final Long completedAt;
    private final Long failedAt;
    private final String location;
    private final String configId;
    private final Long expiry;
    private final String error;

    @JsonCreator
    public ServiceDumpReport(@JsonProperty(REQUESTED_AT_FIELD) Long requestedAt,
                             @JsonProperty(STARTED_AT_FIELD) Long startedAt,
                             @JsonProperty(COMPLETED_AT_FIELD) Long completedAt,
                             @JsonProperty(FAILED_AT_FIELD) Long failedAt,
                             @JsonProperty(LOCATION_FIELD) String location,
                             @JsonProperty(CONFIG_ID_FIELD) String configId,
                             @JsonProperty(EXPIRY_FIELD) Long expiry,
                             @JsonProperty(ERROR_FIELD) String error) {
        super(null, null);
        this.requestedAt = requestedAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
        this.location = location;
        this.configId = configId;
        this.expiry = expiry;
        this.error = error;
    }

    @JsonGetter(REQUESTED_AT_FIELD) public Long requestedAt() { return requestedAt; }
    @JsonGetter(STARTED_AT_FIELD) public Long startedAt() { return startedAt; }
    @JsonGetter(COMPLETED_AT_FIELD) public Long completedAt() { return completedAt; }
    @JsonGetter(FAILED_AT_FIELD) public Long failedAt() { return failedAt; }
    @JsonGetter(LOCATION_FIELD) public String location() { return location; }
    @JsonGetter(CONFIG_ID_FIELD) public String configId() { return configId; }
    @JsonGetter(EXPIRY_FIELD) public Long expiry() { return expiry; }
    @JsonGetter(ERROR_FIELD) public String error() { return error; }
}
