package uk.gov.nationalarchives.rotate

import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.ComponentsResource
import org.keycloak.common.util.MultivaluedHashMap
import org.keycloak.representations.idm.ComponentRepresentation
import org.slf4j.Logger
import org.slf4j.impl.SimpleLoggerFactory
import uk.gov.nationalarchives.rotate.MessageSender.Message

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

class RotateRealmKeys(client: Keycloak) {
  val logger: Logger = new SimpleLoggerFactory().getLogger(this.getClass.getName)

  def rotate(): Message = Try {
    val realm = client.realm("tdr")
    val components: ComponentsResource = realm.components()
    val keyProvider = "org.keycloak.keys.KeyProvider"
    val keyName = "rsa-generated"


    val rsaKeys = components.query("tdr", keyProvider, keyName)

    def filterKeys(filterValue: String): List[ComponentRepresentation] = rsaKeys.asScala.filter(key => key.getConfig.get("active").asScala.toList.head == filterValue).toList

    val inactiveKeys: List[ComponentRepresentation] = filterKeys("false")
    val activeKeys: List[ComponentRepresentation] = filterKeys("true")
    //Delete the passive keys from last week, no-one should be using them.
    inactiveKeys.foreach(key => {
      components.component(key.getId).remove()
    })

    val config = new MultivaluedHashMap[String, String]()
    config.putSingle("priority", "100")
    config.putSingle("enabled", "true")
    config.putSingle("active", "true")
    config.putSingle("keySize", "2048")
    config.putSingle("algorithm", "RS256")

    val componentRepresentation = new ComponentRepresentation()
    componentRepresentation.setName("rsa-generated")
    componentRepresentation.setProviderId("rsa-generated")
    componentRepresentation.setProviderType(keyProvider)
    componentRepresentation.setParentId("tdr")
    componentRepresentation.setConfig(config)

    //Add a new key
    components.add(componentRepresentation)

    // Set the original active key to passive
    activeKeys.foreach(key => {
      val config: MultivaluedHashMap[String, String] = key.getConfig
      config.putSingle("active", "false")
      key.setConfig(config)
      components.component(key.getId).update(key)
      s"Active key ${key.getName} has been made passive"
    })
  } match {
    case Failure(exception) =>
      logger.error(exception.getMessage, exception)
      Message(s"Realm key rotation for the TDR realm has failed ${exception.getMessage}")
    case Success(_) => Message("Realm key rotation for the TDR realm completed successfully")
  }

}
object RotateRealmKeys {
  def apply(client: Keycloak) = new RotateRealmKeys(client)
}
