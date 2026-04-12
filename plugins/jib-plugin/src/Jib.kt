import com.google.cloud.tools.jib.api.*
import com.google.cloud.tools.jib.api.buildplan.Platform
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory
import org.jetbrains.amper.plugins.*
import java.nio.file.Path

@TaskAction
fun buildAndPush(
    @Input runtimeClasspath: Classpath,
    container: ContainerSettings,
    baseImage: BaseImageSettings,
    targetImage: TargetImageSettings,
) {
    val containerizer = Containerizer.to(targetImage.toRegistryImages())
    targetImage.tags.forEach { containerizer.withAdditionalTag(it) }
    jibContainerBuilder(runtimeClasspath, container, baseImage).containerize(containerizer)
}

@TaskAction
fun buildTar(
    @Input runtimeClasspath: Classpath,
    container: ContainerSettings,
    baseImage: BaseImageSettings,
    targetImageName: String,
    @Output outputTar: Path,
) {
    jibContainerBuilder(runtimeClasspath, container, baseImage)
        .containerize(Containerizer.to(TarImage.at(outputTar).named(targetImageName)))
}

@TaskAction
fun buildToDockerDaemon(
    @Input runtimeClasspath: Classpath,
    container: ContainerSettings,
    baseImage: BaseImageSettings,
    targetImageName: String,
) {
    jibContainerBuilder(runtimeClasspath, container, baseImage)
        .containerize(Containerizer.to(DockerDaemonImage.named(ImageReference.parse(targetImageName))))
}

private fun jibContainerBuilder(
    runtimeClasspath: Classpath,
    container: ContainerSettings,
    baseImage: BaseImageSettings,
): JibContainerBuilder = JavaContainerBuilder.from(baseImage.toRegistryImage())
    .addDependencies(runtimeClasspath.resolvedFiles)
    .addJvmFlags(container.jvmArgs)
    .setMainClass(container.mainClass)
    .toContainerBuilder()
    .apply {
        if (container.entryPoint != null) {
            setEntrypoint(container.entryPoint)
        }
        if (container.platforms.isNotEmpty()) {
            setPlatforms(container.platforms.map { Platform(it.architecture, it.os) }.toSet())
        }
    }

private fun BaseImageSettings.toRegistryImage(): RegistryImage {
    val imageReference = ImageReference.parse(fullName)
    val registryImage = RegistryImage.named(imageReference)
    registryImage.configureCredentials(imageReference, credHelper, auth)
    return registryImage
}

private fun TargetImageSettings.toRegistryImages(): RegistryImage {
    val imageReference = ImageReference.parse(name)
    val registryImage = RegistryImage.named(imageReference)
    registryImage.configureCredentials(imageReference, credHelper, auth)
    return registryImage
}

private fun RegistryImage.configureCredentials(
    imageReference: ImageReference,
    credHelper: String?,
    auth: Credentials?,
) {
    val credentialRetrieverFactory = CredentialRetrieverFactory.forImage(imageReference) { logEvent ->
        println("${logEvent.level} ${logEvent.message}")
    }
    addCredentialRetriever(credentialRetrieverFactory.wellKnownCredentialHelpers())
    addCredentialRetriever(credentialRetrieverFactory.dockerConfig())
    if (credHelper != null) {
        addCredentialRetriever(credentialRetrieverFactory.dockerCredentialHelper(credHelper))
    }
    if (auth != null) {
        val basicAuth = credentialRetrieverFactory.known(Credential.from(auth.username, auth.password), "basic auth")
        addCredentialRetriever(basicAuth)
    }
}
