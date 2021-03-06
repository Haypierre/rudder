package com.normation.rudder.rest

import com.normation.eventlog.ModificationId
import com.normation.rudder.UserService
import com.normation.rudder.api.RoApiAccountRepository
import com.normation.rudder.api.WoApiAccountRepository
import com.normation.rudder.api._
import com.normation.rudder.api.{ApiAuthorization => ApiAuthz}
import com.normation.rudder.rest.ApiAccountSerialisation._
import com.normation.rudder.rest.RestUtils._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Loggable
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JArray
import net.liftweb.json.JsonDSL._
import org.joda.time.DateTime

class RestApiAccounts (
    readApi        : RoApiAccountRepository
  , writeApi       : WoApiAccountRepository
  , restExtractor  : RestExtractorService
  , tokenGenerator : TokenGenerator
  , uuidGen        : StringUuidGenerator
  , userService    : UserService
) extends RestHelper with Loggable {

  val tokenSize = 32

  //used in ApiAccounts snippet to get the context path
  //of that service
  val relativePath = "secure" :: "apiaccounts" :: Nil

  serve {
    case Get("secure" :: "apiaccounts" :: Nil, req) =>
      readApi.getAllStandardAccounts match {
        case Full(accountSeq) =>
          val accounts = ("accounts" -> JArray(accountSeq.toList.map(_.toJson)))
          toJsonResponse(None,accounts)("getAllAccounts",true)
        case eb : EmptyBox =>
          logger.error(s"Could not get accounts cause : ${(eb ?~ "could not get account").msg}")
          toJsonError(None,s"Could not get accounts cause : ${(eb ?~ "could not get account").msg}")("getAllAccounts",true)

      }

    case "secure" :: "apiaccounts" :: Nil JsonPut body -> req =>
      req.json match {
        case Full(json) =>
        restExtractor.extractApiAccountFromJSON(json) match {
          case Full(restApiAccount) =>
            if (restApiAccount.name.isDefined) {
              // generate the id for creation
              val id = ApiAccountId(uuidGen.newUuid)
              val now = DateTime.now
              //by default, token expires after one month
              val expiration = restApiAccount.expiration.getOrElse(Some(now.plusMonths(1)))
              val acl = restApiAccount.authz.getOrElse(ApiAuthz.None)

              val account = ApiAccount(
                  id
                , ApiAccountKind.PublicApi(acl, expiration)
                , restApiAccount.name.get ,ApiToken(tokenGenerator.newToken(tokenSize))
                , restApiAccount.description.getOrElse("")
                , restApiAccount.enabled.getOrElse(true)
                , now
                , now
              )
              writeApi.save(account, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor) match {
                case Full(_) =>
                  val accounts = ("accounts" -> JArray(List(account.toJson)))
                  toJsonResponse(None,accounts)("updateAccount",true)

                case eb : EmptyBox =>
                  logger.error(s"Could not create account cause : ${(eb ?~ "could not save account").msg}")
                  toJsonError(None,s"Could not create account cause : ${(eb ?~ "could not save account").msg}")("updateAccount",true)
              }
            } else {
              logger.error(s"Could not create account cause : could not get account")
              toJsonError(None,s"Could not create account cause : could not get account")("updateAccount",true)
            }

          case eb : EmptyBox =>
            logger.error(s"Could not create account cause : ${(eb ?~ "could not extract data from JSON").msg}")
            toJsonError(None,s"Could not create account cause : ${(eb ?~ "could not extract data from JSON").msg}")("updateAccount",true)
        }
        case eb:EmptyBox=>
          logger.error("No Json data sent")
          toJsonError(None, "No Json data sent")("updateAccount",true)
      }

    case "secure" :: "apiaccounts" :: token :: Nil JsonPost body -> req =>
      val apiToken = ApiToken(token)
      req.json match {
        case Full(json) =>
        restExtractor.extractApiAccountFromJSON(json) match {
          case Full(restApiAccount) =>
            readApi.getByToken(apiToken) match {
              case Full(Some(account)) =>
                val updateAccount = restApiAccount.update(account)
                save(updateAccount)

              case Full(None) =>
                logger.error(s"Could not update account with token $token cause : could not get account")
                toJsonError(None,s"Could not update account with token $token cause : could not get account")("updateAccount",true)
              case eb : EmptyBox =>
                logger.error(s"Could not update account with token $token cause : ${(eb ?~ "could not get account").msg}")
                toJsonError(None,s"Could not update account with token $token cause : ${(eb ?~ "could not get account").msg}")("updateAccount",true)
            }
          case eb : EmptyBox =>
            logger.error(s"Could not update account with token $token cause : ${(eb ?~ "could not extract data from JSON").msg}")
            toJsonError(None,s"Could not update account with token $token cause : ${(eb ?~ "could not extract data from JSON").msg}")("updateAccount",true)
        }
        case eb:EmptyBox=>
          toJsonError(None, "No Json data sent")("updateAccount",true)
      }

    case Delete("secure" :: "apiaccounts" :: token :: Nil, req) =>
      val apiToken = ApiToken(token)
      readApi.getByToken(apiToken) match {
        case Full(Some(account)) =>
          writeApi.delete(account.id, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor) match {
            case Full(_) =>
              val accounts = ("accounts" -> JArray(List(account.toJson)))
              toJsonResponse(None,accounts)("deleteAccount",true)

            case eb : EmptyBox =>
              toJsonError(None,s"Could not delete account with token $token cause : ${(eb ?~ "could not delete account").msg}")("deleteAccount",true)
          }

        case Full(None) =>
          toJsonError(None,s"Could not delete account with token $token cause : could not get account")("deleteAccount",true)
        case eb : EmptyBox =>
          toJsonError(None,s"Could not delete account with token $token cause : ${(eb ?~ "could not get account").msg}")("deleteAccount",true)
      }

    case Post("secure" :: "apiaccounts" :: token :: "regenerate" :: Nil, req) =>
      val apiToken = ApiToken(token)
      readApi.getByToken(apiToken) match {
        case Full(Some(account)) =>
          val newToken = ApiToken(tokenGenerator.newToken(tokenSize))
          val generationDate = DateTime.now
          writeApi.save(
              account.copy(token = newToken, tokenGenerationDate = generationDate)
            , ModificationId(uuidGen.newUuid)
            , userService.getCurrentUser.actor) match {
            case Full(account) =>
              val accounts = ("accounts" -> JArray(List(account.toJson)))
              toJsonResponse(None,accounts)("regenerateAccount",true)

            case eb : EmptyBox =>
              logger.error(s"Could not regenerate account with token $token cause : ${(eb ?~ "could not save account").msg}")
              toJsonError(None,s"Could not regenerate account with token $token cause : ${(eb ?~ "could not save account").msg}")("regenerateAccount",true)
          }

        case Full(None) =>
          logger.error(s"Could not regenerate account with token $token cause could not get account")
          toJsonError(None,s"Could not regenerate account with token $token cause : could not get account")("regenerateAccount",true)
        case eb : EmptyBox =>
          logger.error(s"Could not regenerate account with token $token cause : ${(eb ?~ "could not get account").msg}")
          toJsonError(None,s"Could not regenerate account with token $token cause : ${(eb ?~ "could not get account").msg}")("regenerateAccount",true)
      }

  }

  def save(account:ApiAccount) : LiftResponse = {
    writeApi.save(account, ModificationId(uuidGen.newUuid), userService.getCurrentUser.actor) match {
      case Full(res) =>
        val accounts = ("accounts" -> JArray(List(res.toJson)))
        toJsonResponse(None,accounts)("updateAccount",true)

      case eb : EmptyBox =>
        toJsonError(None, s"Could not update account '${account.name.value}' cause : ${(eb ?~ "could not save account").msg}")("updateAccount",true)
    }
  }

}

case class RestApiAccount(
    id          : Option[ApiAccountId]
  , name        : Option[ApiAccountName]
  , description : Option[String]
  , enabled     : Option[Boolean]
  , oldId       : Option[ApiAccountId]
  , expiration  : Option[Option[DateTime]]
  , authz       : Option[ApiAuthz]
) {

  // Id cannot change if already defined
  def update(account : ApiAccount) = {
    val nameUpdate   = name.getOrElse(account.name)
    val enableUpdate = enabled.getOrElse(account.isEnabled)
    val descUpdate   = description.getOrElse(account.description)
    val kind = account.kind match {
      case ApiAccountKind.PublicApi(a, e) =>
        ApiAccountKind.PublicApi(authz.getOrElse(a), expiration.getOrElse(e))
      case x => x
    }

    account.copy(name = nameUpdate, isEnabled = enableUpdate, description = descUpdate, kind = kind)
  }
}
