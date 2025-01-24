package uk.gov.nationalarchives.rotate

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ApplicationConfig._

class ApplicationConfigSpec extends AnyFlatSpec with Matchers {
  "the config object" should "provide the correct config" in {
    environment should equal("test")
    authUrl should equal("http://localhost:8000")
    userAdminClient should equal("test-client")
    ssmEndpoint should equal("http://localhost:8080")
    snsEndpoint should equal("test")
    snsTopic should equal("arn:aws:sns:region:account:name")
    consignmentApiConnectionName should equal("connectionName")
  }
}
