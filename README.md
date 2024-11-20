# Rotate Keycloak Secrets

This is the code for a lambda which will be run on a schedule using Cloudwatch events. 

It is used to rotate the client secrets for each of the confidential clients in Keycloak. It does this by:

* Rotating the client secret using the Keycloak Admin Client and getting the new value.
* Overwriting the existing value in the parameter store with the new value.
* Restarting the relevant ECS services.

It is also used to rotate the realm keys for the TDR realm. These are the keys that are used to sign the access and refresh tokens for the users.
As the master realm doesn't issue tokens, we don't need to update the keys there. The process for rotating the realm keys is:
* Delete the keys which were set to passive from the previous weeks run.
* Create a new realm key. This key will be used to sign any new tokens.
* Mark the previous active key as passive. No new tokens will be signed with this key but existing ones will still work. This will then be deleted on the next lambda run.


## Running Locally
There is a `LambdaRunner` class which you can run from IntelliJ or by using `sbt run`. 
You will need AWS credentials with permissions to get and put parameters for each of the Keycloak client secret parameters and to update the front end ECS service  

You will also need to set the following environment variables. The examples are for integration but can be changed.
```
AUTH_URL: https://auth.tdr-integration.nationalarchives.gov.uk
AUTH_SECRET_PATH: /intg/keycloak/rotate_secrets_client/secret
ENVIRONMENT: intg
SNS_TOPIC: arn:aws:sns:eu-west-2:${intg_account_number}:tdr-notifications-intg
```
