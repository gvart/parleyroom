package com.gvart.parleyroom.common.storage

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.InputStream
import java.net.URI
import java.util.UUID

class StorageService(
    private val config: StorageConfig,
) : java.io.Closeable {
    private val log = LoggerFactory.getLogger(StorageService::class.java)

    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(config.accessKey, config.secretKey)
    )

    private val s3Configuration = S3Configuration.builder()
        .pathStyleAccessEnabled(config.pathStyleAccess)
        .build()

    private val s3Client: S3Client = S3Client.builder()
        .region(Region.of(config.region))
        .credentialsProvider(credentials)
        .endpointOverride(URI.create(config.endpoint))
        .serviceConfiguration(s3Configuration)
        .build()

    init {
        runCatching { ensureBucket() }.onFailure {
            log.warn("Storage bucket '{}' check failed at startup: {}. Bucket will be created on first use.", config.bucket, it.message)
        }
    }

    private fun ensureBucket() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(config.bucket).build())
        } catch (e: Exception) {
            val missing = e is NoSuchBucketException || (e is S3Exception && e.statusCode() == 404)
            if (!missing) throw e
            log.info("Creating storage bucket '{}'", config.bucket)
            s3Client.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build())
        }
    }

    fun buildKey(teacherId: UUID, materialId: UUID, filename: String): String {
        val safe = sanitize(filename)
        return "$teacherId/$materialId/$safe"
    }

    fun buildAvatarKey(userId: UUID, filename: String): String {
        val safe = sanitize(filename)
        return "avatars/$userId/$safe"
    }

    fun healthCheck() {
        s3Client.headBucket(HeadBucketRequest.builder().bucket(config.bucket).build())
    }

    fun upload(key: String, contentType: String, stream: InputStream, contentLength: Long) {
        val put = PutObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .build()
        s3Client.putObject(put, RequestBody.fromInputStream(stream, contentLength))
    }

    fun stream(key: String): ResponseInputStream<GetObjectResponse> {
        val get = GetObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .build()
        return s3Client.getObject(get)
    }

    fun delete(key: String) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build())
        } catch (_: NoSuchKeyException) {
            // already gone
        }
    }

    override fun close() {
        log.info("Closing StorageService: shutting down S3 client")
        runCatching { s3Client.close() }.onFailure { log.warn("Error closing S3 client", it) }
    }

    private fun sanitize(filename: String): String {
        val trimmed = filename.trim().ifEmpty { "file" }
        return trimmed.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
