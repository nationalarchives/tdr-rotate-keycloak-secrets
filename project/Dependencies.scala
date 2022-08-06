import sbt._

object Dependencies {
  private val circeVersion = "0.14.2"

  lazy val awsSns = "software.amazon.awssdk" % "sns" % "2.17.234"
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.17.233"
  lazy val awsEcs = "software.amazon.awssdk" % "ecs" % "2.17.233"
  lazy val circeCore = "io.circe" %% "circe-core" % circeVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  lazy val keycloakAdminClient = "org.keycloak" % "keycloak-admin-client" % "18.0.0"
  lazy val lambdaCore = "com.amazonaws" % "aws-lambda-java-core" % "1.2.1"
  lazy val mockito = "org.mockito" %% "mockito-scala" % "1.17.12"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.12"
  lazy val slf4j = "org.slf4j" % "slf4j-simple" % "1.7.36"
  lazy val typesafe = "com.typesafe" % "config" % "1.4.2"
  lazy val wiremock = "com.github.tomakehurst" % "wiremock" % "2.27.2"
}
