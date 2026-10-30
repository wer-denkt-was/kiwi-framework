package de.kiwi.storage.infrastructure.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.sdk.kotlin.services.s3.headBucket
import aws.sdk.kotlin.services.s3.model.BucketCannedAcl
import aws.sdk.kotlin.services.s3.model.NoSuchBucket
import aws.sdk.kotlin.services.s3.model.NotFound
import de.kiwi.api.util.slf4j

interface S3BucketInitializer {
    suspend fun initializeBucket(client: S3Client, bucket: String, publicRead: Boolean)
}

object DoNothingBucketInitializer : S3BucketInitializer {
    override suspend fun initializeBucket(client: S3Client, bucket: String, publicRead: Boolean) {}
}

object ValidateBucketInitializer : S3BucketInitializer {
    override suspend fun initializeBucket(client: S3Client, bucket: String, publicRead: Boolean) {
        if (!client.checkBucketExists(bucket)) {
            throw IllegalStateException("S3 validation failed: Missing bucket $bucket")
        }
    }
}

object AutoCreateBucketInitializer : S3BucketInitializer {
    override suspend fun initializeBucket(client: S3Client, bucket: String, publicRead: Boolean) {
        if (!client.checkBucketExists(bucket)) {
            client.autoCreateBucket(bucket, publicRead)
        }
    }
}

private val log = slf4j<S3BucketInitializer>()

private suspend fun S3Client.checkBucketExists(name: String): Boolean {
    return try {
        headBucket { bucket = name }
        log.info("S3 Bucket [$name] already exists")
        true
    } catch (ex: NotFound) {
        log.error("S3 Bucket [$name] not found, HEAD response: ${ex.message}")
        false
    } catch (ex: NoSuchBucket) {
        log.error("S3 Bucket [$name] does not exist, HEAD response: ${ex.message}")
        false
    }
}

private suspend fun S3Client.autoCreateBucket(name: String, publicRead: Boolean) {
    log.info("S3 Bucket [$name] creating new bucket ...")
    val response = createBucket {
        bucket = name
        acl = if (publicRead) BucketCannedAcl.PublicRead else BucketCannedAcl.Private
    }
    log.info("S3 Bucket [$name] created successfully, POST response: $response")
}
