package de.kiwi.storage.web

import de.kiwi.storage.infrastructure.local.LocalFileStorageService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.util.*

@RestController
@Tag(name = "kiwi.FileEntity")
class LocalFileController {
    @Autowired(required = false)
    var localFileStorageService: LocalFileStorageService? = null

    @GetMapping("${LocalFileStorageService.LOCAL_STORAGE_ENDPOINT}/{uuid}/{filename}")
    fun loadFromLocalStorage(
        @PathVariable uuid: UUID,
        @PathVariable filename: String,
        @RequestParam(LocalFileStorageService.LOCAL_STORAGE_TOKEN_PARAM, required = false) signedToken: String?,
    ): ResponseEntity<Resource> {
        // ensure local storage is available
        val storage = localFileStorageService
            ?: return ResponseEntity.internalServerError().build()

        // access file-resource from storage, access is denied if no resource is returned
        val fileResource = storage.accessFileResource(uuid, filename, signedToken)
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        // wrap file-resource into response-entity, returns 404 if file doesn't actually exist
        return fileResource.toResponseEntity()
    }

    private fun Resource.toResponseEntity(): ResponseEntity<Resource> {
        if (!exists()) {
            return ResponseEntity.notFound().build()
        }
        val filePath = file.toPath()
        val contentType = Files.probeContentType(filePath) ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .contentLength(contentLength())
            .body(this)
    }
}
