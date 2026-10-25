package com.devassisthub.commit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits applied while reading an uploaded ZIP (uncompressed sizes). */
@ConfigurationProperties("commit.upload")
public record UploadProperties(int maxFiles, long maxFileBytes, long maxTotalBytes) {
}
