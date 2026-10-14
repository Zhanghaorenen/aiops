package com.aiops.backend.vo;

public class ServerVO{

    private Long id;
    private String name;
    private String ip;
    private String status;
    private Double cpuUsage;
    private Double memoryUsage;
    private Double diskUsage;
    private String hostname;
    private String osName;
    private String osVersion;
    private String architecture;
    private Integer cpuCores;
    private Long memoryBytes;
    private java.time.Instant lastSeenAt;
    private java.time.Instant latestMetricAt;

    public ServerVO(Long id,String name,String ip, String status, Double cpuUsage, Double memoryUsage){
        this.id = id;
        this.name = name;
        this.ip = ip;
        this.status = status;
        this.cpuUsage = cpuUsage;
        this.memoryUsage = memoryUsage;
    }

    public ServerVO(Long id, String name, String ip, String status, Double cpuUsage, Double memoryUsage,
            Double diskUsage, String hostname, String osName, String osVersion, String architecture,
            Integer cpuCores, Long memoryBytes, java.time.Instant lastSeenAt, java.time.Instant latestMetricAt) {
        this(id, name, ip, status, cpuUsage, memoryUsage);
        this.diskUsage = diskUsage;
        this.hostname = hostname;
        this.osName = osName;
        this.osVersion = osVersion;
        this.architecture = architecture;
        this.cpuCores = cpuCores;
        this.memoryBytes = memoryBytes;
        this.lastSeenAt = lastSeenAt;
        this.latestMetricAt = latestMetricAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIp() {
        return ip;
    }

    public String getStatus() {
        return status;
    }

    public Double getCpuUsage() {
        return cpuUsage;
    }

    public Double getMemoryUsage() {
        return memoryUsage;
    }

    public String getHostname() { return hostname; }
    public String getOsName() { return osName; }
    public String getOsVersion() { return osVersion; }
    public String getArchitecture() { return architecture; }
    public Integer getCpuCores() { return cpuCores; }
    public Long getMemoryBytes() { return memoryBytes; }
    public java.time.Instant getLastSeenAt() { return lastSeenAt; }
    public Double getDiskUsage() { return diskUsage; }
    public java.time.Instant getLatestMetricAt() { return latestMetricAt; }
}
