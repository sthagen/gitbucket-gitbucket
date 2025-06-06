package gitbucket.core.controller

import java.time.{LocalDateTime, ZoneOffset}
import java.util.Date
import gitbucket.core.settings.html
import gitbucket.core.model.{RepositoryWebHook, WebHook}
import gitbucket.core.service.*
import gitbucket.core.service.WebHookService.*
import gitbucket.core.util.*
import gitbucket.core.util.JGitUtil.*
import gitbucket.core.util.SyntaxSugars.*
import gitbucket.core.util.Implicits.*
import gitbucket.core.util.Directory.*
import gitbucket.core.model.WebHookContentType
import gitbucket.core.model.activity.RenameRepositoryInfo
import org.scalatra.forms.*
import org.scalatra.i18n.Messages
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId

import scala.util.Using
import org.scalatra.{Forbidden, Ok}

class RepositorySettingsController
    extends RepositorySettingsControllerBase
    with RepositoryService
    with AccountService
    with WebHookService
    with ProtectedBranchService
    with CommitStatusService
    with DeployKeyService
    with CustomFieldsService
    with ActivityService
    with OwnerAuthenticator
    with UsersAuthenticator
    with RequestCache

trait RepositorySettingsControllerBase extends ControllerBase {
  self: RepositoryService & AccountService & WebHookService & ProtectedBranchService & CommitStatusService &
    DeployKeyService & CustomFieldsService & ActivityService & OwnerAuthenticator & UsersAuthenticator =>

  // for repository options
  private case class OptionsForm(
    description: Option[String],
    isPrivate: Boolean,
    issuesOption: String,
    externalIssuesUrl: Option[String],
    wikiOption: String,
    externalWikiUrl: Option[String],
    allowFork: Boolean,
    mergeOptions: Seq[String],
    defaultMergeOption: String,
    safeMode: Boolean
  )

  private val optionsForm = mapping(
    "description" -> trim(label("Description", optional(text()))),
    "isPrivate" -> trim(label("Repository Type", boolean())),
    "issuesOption" -> trim(label("Issues Option", text(required, featureOption))),
    "externalIssuesUrl" -> trim(label("External Issues URL", optional(text(maxlength(200))))),
    "wikiOption" -> trim(label("Wiki Option", text(required, featureOption))),
    "externalWikiUrl" -> trim(label("External Wiki URL", optional(text(maxlength(200))))),
    "allowFork" -> trim(label("Allow Forking", boolean())),
    "mergeOptions" -> mergeOptions,
    "defaultMergeOption" -> trim(label("Default merge strategy", text(required))),
    "safeMode" -> trim(label("XSS protection", boolean()))
  )(OptionsForm.apply).verifying { form =>
    if (!form.mergeOptions.contains(form.defaultMergeOption)) {
      Seq("defaultMergeOption" -> s"This merge strategy isn't enabled.")
    } else Nil
  }

  // for default branch
  private case class DefaultBranchForm(defaultBranch: String)

  private val defaultBranchForm = mapping(
    "defaultBranch" -> trim(label("Default Branch", text(required, maxlength(100))))
  )(DefaultBranchForm.apply)

  // for deploy key
  private case class DeployKeyForm(title: String, publicKey: String, allowWrite: Boolean)

  private val deployKeyForm = mapping(
    "title" -> trim(label("Title", text(required, maxlength(100)))),
    "publicKey" -> trim2(label("Key", text(required))), // TODO duplication check in the repository?
    "allowWrite" -> trim(label("Key", boolean()))
  )(DeployKeyForm.apply)

  // for web hook url addition
  private case class WebHookForm(
    url: String,
    events: Set[WebHook.Event],
    ctype: WebHookContentType,
    token: Option[String]
  )

  private def webHookForm(update: Boolean) =
    mapping(
      "url" -> trim(label("url", text(required, webHook(update)))),
      "events" -> webhookEvents,
      "ctype" -> label("ctype", text()),
      "token" -> optional(trim(label("token", text(maxlength(100)))))
    )((url, events, ctype, token) => WebHookForm(url, events, WebHookContentType.valueOf(ctype), token))

  // for rename repository
  private case class RenameRepositoryForm(repositoryName: String)

  private val renameForm = mapping(
    "repositoryName" -> trim(
      label("New repository name", text(required, maxlength(100), repository, renameRepositoryName))
    )
  )(RenameRepositoryForm.apply)

  // for transfer ownership
  private case class TransferOwnerShipForm(newOwner: String)

  private val transferForm = mapping(
    "newOwner" -> trim(label("New owner", text(required, transferUser)))
  )(TransferOwnerShipForm.apply)

  // for custom field
  private case class CustomFieldForm(
    fieldName: String,
    fieldType: String,
    constraints: Option[String],
    enableForIssues: Boolean,
    enableForPullRequests: Boolean
  )

  private val customFieldForm = mapping(
    "fieldName" -> trim(label("Field name", text(required, maxlength(100)))),
    "fieldType" -> trim(label("Field type", text(required))),
    "constraints" -> trim(label("Constraints", optional(text()))),
    "enableForIssues" -> trim(label("Enable for issues", boolean(required))),
    "enableForPullRequests" -> trim(label("Enable for pull requests", boolean(required))),
  )(CustomFieldForm.apply)

  /**
   * Redirect to the Options page.
   */
  get("/:owner/:repository/settings")(ownerOnly { repository =>
    redirect(s"/${repository.owner}/${repository.name}/settings/options")
  })

  /**
   * Display the Options page.
   */
  get("/:owner/:repository/settings/options")(ownerOnly {
    html.options(_, flash.get("info"))
  })

  /**
   * Save the repository options.
   */
  post("/:owner/:repository/settings/options", optionsForm)(ownerOnly { (form, repository) =>
    saveRepositoryOptions(
      repository.owner,
      repository.name,
      form.description,
      repository.repository.parentUserName.map { _ =>
        repository.repository.isPrivate
      } getOrElse form.isPrivate,
      form.issuesOption,
      form.externalIssuesUrl,
      form.wikiOption,
      form.externalWikiUrl,
      form.allowFork,
      form.mergeOptions,
      form.defaultMergeOption,
      form.safeMode
    )
    flash.update("info", "Repository settings has been updated.")
    redirect(s"/${repository.owner}/${repository.name}/settings/options")
  })

  /** branch settings */
  get("/:owner/:repository/settings/branches")(ownerOnly { repository =>
    val protecteions = getProtectedBranchList(repository.owner, repository.name)
    html.branches(repository, protecteions, flash.get("info"))
  })

  /** Update default branch */
  post("/:owner/:repository/settings/update_default_branch", defaultBranchForm)(ownerOnly { (form, repository) =>
    if (!repository.branchList.contains(form.defaultBranch)) {
      redirect(s"/${repository.owner}/${repository.name}/settings/branches")
    } else {
      saveRepositoryDefaultBranch(repository.owner, repository.name, form.defaultBranch)
      // Change repository HEAD
      Using.resource(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
        git.getRepository.updateRef(Constants.HEAD, true).link(Constants.R_HEADS + form.defaultBranch)
      }
      flash.update("info", "Repository default branch has been updated.")
      redirect(s"/${repository.owner}/${repository.name}/settings/branches")
    }
  })

  /** Branch protection for branch */
  get("/:owner/:repository/settings/branches/*")(ownerOnly { repository =>
    import gitbucket.core.api.*
    val branch = params("splat")

    if (!repository.branchList.contains(branch)) {
      redirect(s"/${repository.owner}/${repository.name}/settings/branches")
    } else {
      val protection = ApiBranchProtection(getProtectedBranchInfo(repository.owner, repository.name, branch))
      val lastWeeks = getRecentStatusContexts(
        repository.owner,
        repository.name,
        Date.from(LocalDateTime.now.minusWeeks(1).toInstant(ZoneOffset.UTC))
      ).toSet
      val knownContexts = (lastWeeks ++ protection.status.contexts).toSeq.sortBy(identity)
      html.branchprotection(repository, branch, protection, knownContexts, flash.get("info"))
    }
  })

  /**
   * Display the Collaborators page.
   */
  get("/:owner/:repository/settings/collaborators")(ownerOnly { repository =>
    html.collaborators(
      getCollaborators(repository.owner, repository.name),
      getAccountByUserName(repository.owner).get.isGroupAccount,
      repository
    )
  })

  post("/:owner/:repository/settings/collaborators")(ownerOnly { repository =>
    val collaborators = params("collaborators")
    removeCollaborators(repository.owner, repository.name)
    collaborators.split(",").withFilter(_.nonEmpty).foreach { collaborator =>
      val userName :: role :: Nil = collaborator.split(":").toList
      addCollaborator(repository.owner, repository.name, userName, role)
    }
    redirect(s"/${repository.owner}/${repository.name}/settings/collaborators")
  })

  /**
   * Display the web hook page.
   */
  get("/:owner/:repository/settings/hooks")(ownerOnly { repository =>
    html.hooks(getWebHooks(repository.owner, repository.name), repository, flash.get("info"))
  })

  /**
   * Display the web hook edit page.
   */
  get("/:owner/:repository/settings/hooks/new")(ownerOnly { repository =>
    val webhook = RepositoryWebHook(
      userName = repository.owner,
      repositoryName = repository.name,
      url = "",
      ctype = WebHookContentType.FORM,
      token = None
    )
    html.edithook(webhook, Set(WebHook.Push), repository, create = true)
  })

  /**
   * Add the web hook URL.
   */
  post("/:owner/:repository/settings/hooks/new", webHookForm(false))(ownerOnly { (form, repository) =>
    addWebHook(repository.owner, repository.name, form.url, form.events, form.ctype, form.token)
    flash.update("info", s"Webhook ${form.url} created")
    redirect(s"/${repository.owner}/${repository.name}/settings/hooks")
  })

  /**
   * Delete the web hook URL.
   */
  get("/:owner/:repository/settings/hooks/delete")(ownerOnly { repository =>
    deleteWebHook(repository.owner, repository.name, params("url"))
    flash.update("info", s"Webhook ${params("url")} deleted")
    redirect(s"/${repository.owner}/${repository.name}/settings/hooks")
  })

  /**
   * Send the test request to registered web hook URLs.
   */
  ajaxPost("/:owner/:repository/settings/hooks/test")(ownerOnly { repository =>
    def _headers(h: Array[org.apache.http.Header]): Array[Array[String]] =
      h.map { h =>
        Array(h.getName, h.getValue)
      }

    Using.resource(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
      import scala.concurrent.duration.*
      import scala.concurrent.*
      import scala.jdk.CollectionConverters.*
      import scala.util.control.NonFatal
      import org.apache.http.util.EntityUtils
      import scala.concurrent.ExecutionContext.Implicits.global

      val url = params("url")
      val token = Some(params("token"))
      val ctype = WebHookContentType.valueOf(params("ctype"))
      val dummyWebHookInfo = RepositoryWebHook(
        userName = repository.owner,
        repositoryName = repository.name,
        url = url,
        ctype = ctype,
        token = token
      )
      val dummyPayload = {
        val ownerAccount = getAccountByUserName(repository.owner).get
        val commits =
          if (JGitUtil.isEmpty(git)) List.empty
          else
            git.log
              .add(git.getRepository.resolve(repository.repository.defaultBranch))
              .setMaxCount(4)
              .call
              .iterator
              .asScala
              .map(new CommitInfo(_))
              .toList
        val pushedCommit = commits.drop(1)

        WebHookPushPayload(
          git = git,
          sender = ownerAccount,
          refName = "refs/heads/" + repository.repository.defaultBranch,
          repositoryInfo = repository,
          commits = pushedCommit,
          repositoryOwner = ownerAccount,
          oldId = commits.lastOption.map(_.id).map(ObjectId.fromString).getOrElse(ObjectId.zeroId()),
          newId = commits.headOption.map(_.id).map(ObjectId.fromString).getOrElse(ObjectId.zeroId())
        )
      }

      val (webHook, json, reqFuture, resFuture) =
        callWebHook(WebHook.Push, List(dummyWebHookInfo), dummyPayload, context.settings).head

      val toErrorMap: PartialFunction[Throwable, Map[String, String]] = {
        case e: java.net.UnknownHostException                  => Map("error" -> s"Unknown host ${e.getMessage}")
        case _: java.lang.IllegalArgumentException             => Map("error" -> "invalid url")
        case _: org.apache.http.client.ClientProtocolException => Map("error" -> "invalid url")
        case NonFatal(e)                                       => Map("error" -> s"${e.getClass} ${e.getMessage}")
      }

      contentType = formats("json")
      org.json4s.jackson.Serialization.write(
        Map(
          "url" -> url,
          "request" -> Await.result(
            reqFuture
              .map(req =>
                Map(
                  "headers" -> _headers(req.getAllHeaders),
                  "payload" -> json
                )
              )
              .recover(toErrorMap),
            20 seconds
          ),
          "response" -> Await.result(
            resFuture
              .map(res =>
                Map(
                  "status" -> res.getStatusLine.getStatusCode,
                  "body" -> EntityUtils.toString(res.getEntity),
                  "headers" -> _headers(res.getAllHeaders)
                )
              )
              .recover(toErrorMap),
            20 seconds
          )
        )
      )
    }
  })

  /**
   * Display the web hook edit page.
   */
  get("/:owner/:repository/settings/hooks/edit")(ownerOnly { repository =>
    getWebHook(repository.owner, repository.name, params("url")).map { case (webhook, events) =>
      html.edithook(webhook, events, repository, create = false)
    } getOrElse NotFound()
  })

  /**
   * Update web hook settings.
   */
  post("/:owner/:repository/settings/hooks/edit", webHookForm(true))(ownerOnly { (form, repository) =>
    updateWebHook(repository.owner, repository.name, form.url, form.events, form.ctype, form.token)
    flash.update("info", s"webhook ${form.url} updated")
    redirect(s"/${repository.owner}/${repository.name}/settings/hooks")
  })

  /**
   * Display the danger zone.
   */
  get("/:owner/:repository/settings/danger")(ownerOnly {
    html.danger(_, flash.get("info"))
  })

  /**
   * Rename repository.
   */
  post("/:owner/:repository/settings/rename", renameForm)(ownerOnly { (form, repository) =>
    context.withLoginAccount { loginAccount =>
      if (context.settings.basicBehavior.repositoryOperation.rename || loginAccount.isAdmin) {
        if (repository.name != form.repositoryName) {
          // Update database and move git repository
          renameRepository(repository.owner, repository.name, repository.owner, form.repositoryName)
          // Record activity log
          val renameInfo = RenameRepositoryInfo(
            repository.owner,
            form.repositoryName,
            loginAccount.userName,
            repository.name
          )
          recordActivity(renameInfo)
        }
        redirect(s"/${repository.owner}/${form.repositoryName}")
      } else Forbidden()
    }
  })

  /**
   * Transfer repository ownership.
   */
  post("/:owner/:repository/settings/transfer", transferForm)(ownerOnly { (form, repository) =>
    context.withLoginAccount { loginAccount =>
      if (context.settings.basicBehavior.repositoryOperation.transfer || loginAccount.isAdmin) {
        // Change repository owner
        if (repository.owner != form.newOwner) {
          // Update database and move git repository
          renameRepository(repository.owner, repository.name, form.newOwner, repository.name)
          // Record activity log
          val renameInfo = RenameRepositoryInfo(
            form.newOwner,
            repository.name,
            loginAccount.userName,
            repository.owner
          )
          recordActivity(renameInfo)
        }
        redirect(s"/${form.newOwner}/${repository.name}")
      } else Forbidden()
    }
  })

  /**
   * Delete the repository.
   */
  post("/:owner/:repository/settings/delete")(ownerOnly { repository =>
    context.withLoginAccount { loginAccount =>
      if (context.settings.basicBehavior.repositoryOperation.delete || loginAccount.isAdmin) {
        // Delete the repository and related files
        deleteRepository(repository.repository)
        redirect(s"/${repository.owner}")
      } else Forbidden()
    }
  })

  /**
   * Run GC
   */
  post("/:owner/:repository/settings/gc")(ownerOnly { repository =>
    LockUtil.lock(s"${repository.owner}/${repository.name}") {
      Using.resource(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
        git.gc().call()
      }
    }
    flash.update("info", "Garbage collection has been executed.")
    redirect(s"/${repository.owner}/${repository.name}/settings/danger")
  })

  /** List deploy keys */
  get("/:owner/:repository/settings/deploykey")(ownerOnly { repository =>
    html.deploykey(repository, getDeployKeys(repository.owner, repository.name))
  })

  /** Register a deploy key */
  post("/:owner/:repository/settings/deploykey", deployKeyForm)(ownerOnly { (form, repository) =>
    addDeployKey(repository.owner, repository.name, form.title, form.publicKey, form.allowWrite)
    redirect(s"/${repository.owner}/${repository.name}/settings/deploykey")
  })

  /** Delete a deploy key */
  get("/:owner/:repository/settings/deploykey/delete/:id")(ownerOnly { repository =>
    val deployKeyId = params("id").toInt
    deleteDeployKey(repository.owner, repository.name, deployKeyId)
    redirect(s"/${repository.owner}/${repository.name}/settings/deploykey")
  })

  /** Custom fields for issues and pull requests */
  get("/:owner/:repository/settings/issues")(ownerOnly { repository =>
    val customFields = getCustomFields(repository.owner, repository.name)
    html.issues(customFields, repository)
  })

  /** New custom field form */
  get("/:owner/:repository/settings/issues/fields/new")(ownerOnly { repository =>
    html.issuesfieldform(None, repository)
  })

  /** Add custom field */
  ajaxPost("/:owner/:repository/settings/issues/fields/new", customFieldForm)(ownerOnly { (form, repository) =>
    val fieldId = createCustomField(
      repository.owner,
      repository.name,
      form.fieldName,
      form.fieldType,
      if (form.fieldType == "enum") form.constraints else None,
      form.enableForIssues,
      form.enableForPullRequests
    )
    html.issuesfield(getCustomField(repository.owner, repository.name, fieldId).get)
  })

  /** Edit custom field form */
  ajaxGet("/:owner/:repository/settings/issues/fields/:fieldId/edit")(ownerOnly { repository =>
    getCustomField(repository.owner, repository.name, params("fieldId").toInt).map { customField =>
      html.issuesfieldform(Some(customField), repository)
    } getOrElse NotFound()
  })

  /** Update custom field */
  ajaxPost("/:owner/:repository/settings/issues/fields/:fieldId/edit", customFieldForm)(ownerOnly {
    (form, repository) =>
      updateCustomField(
        repository.owner,
        repository.name,
        params("fieldId").toInt,
        form.fieldName,
        form.fieldType,
        if (form.fieldType == "enum") form.constraints else None,
        form.enableForIssues,
        form.enableForPullRequests
      )
      html.issuesfield(getCustomField(repository.owner, repository.name, params("fieldId").toInt).get)
  })

  /** Delete custom field */
  ajaxPost("/:owner/:repository/settings/issues/fields/:fieldId/delete")(ownerOnly { repository =>
    deleteCustomField(repository.owner, repository.name, params("fieldId").toInt)
    Ok()
  })

  /**
   * Provides duplication check for web hook url.
   */
  private def webHook(needExists: Boolean): Constraint =
    new Constraint() {
      override def validate(name: String, value: String, messages: Messages): Option[String] =
        if (getWebHook(params("owner"), params("repository"), value).isDefined != needExists) {
          Some(if (needExists) {
            "URL had not been registered yet."
          } else {
            "URL had been registered already."
          })
        } else {
          None
        }
    }

  private def webhookEvents =
    new ValueType[Set[WebHook.Event]] {
      def convert(name: String, params: Map[String, Seq[String]], messages: Messages): Set[WebHook.Event] = {
        WebHook.Event.values.flatMap { t =>
          params.get(name + "." + t.name).map(_ => t)
        }.toSet
      }
      def validate(name: String, params: Map[String, Seq[String]], messages: Messages): Seq[(String, String)] =
        if (convert(name, params, messages).isEmpty) {
          Seq(name -> messages("error.required").format(name))
        } else {
          Nil
        }
    }

//  /**
//   * Provides Constraint to validate the collaborator name.
//   */
//  private def collaborator: Constraint = new Constraint(){
//    override def validate(name: String, value: String, messages: Messages): Option[String] =
//      getAccountByUserName(value) match {
//        case None => Some("User does not exist.")
////        case Some(x) if(x.isGroupAccount)
////                  => Some("User does not exist.")
//        case Some(x) if(x.userName == params("owner") || getCollaborators(params("owner"), params("repository")).contains(x.userName))
//                  => Some(value + " is repository owner.") // TODO also group members?
//        case _    => None
//      }
//  }

  /**
   * Duplicate check for the rename repository name.
   */
  private def renameRepositoryName: Constraint =
    new Constraint() {
      override def validate(
        name: String,
        value: String,
        params: Map[String, Seq[String]],
        messages: Messages
      ): Option[String] = {
        for {
          repoName <- params.optionValue("repository") if repoName != value
          userName <- params.optionValue("owner")
          _ <- getRepositoryNamesOfUser(userName).find(_ == value)
        } yield {
          "Repository already exists."
        }
      }
    }

  /**
   */
  private def featureOption: Constraint =
    new Constraint() {
      override def validate(
        name: String,
        value: String,
        params: Map[String, Seq[String]],
        messages: Messages
      ): Option[String] =
        if (Seq("DISABLE", "PRIVATE", "PUBLIC", "ALL").contains(value)) None else Some("Option is invalid.")
    }

  /**
   * Provides Constraint to validate the repository transfer user.
   */
  private def transferUser: Constraint =
    new Constraint() {
      override def validate(name: String, value: String, messages: Messages): Option[String] =
        getAccountByUserName(value) match {
          case None    => Some("User does not exist.")
          case Some(x) =>
            if (x.userName == params("owner")) {
              Some("This is current repository owner.")
            } else {
              params.get("repository").flatMap { repositoryName =>
                getRepositoryNamesOfUser(x.userName).find(_ == repositoryName).map { _ =>
                  "User already has same repository."
                }
              }
            }
        }
    }

  private def mergeOptions =
    new ValueType[Seq[String]] {
      override def convert(name: String, params: Map[String, Seq[String]], messages: Messages): Seq[String] = {
        params.getOrElse("mergeOptions", Nil)
      }
      override def validate(
        name: String,
        params: Map[String, Seq[String]],
        messages: Messages
      ): Seq[(String, String)] = {
        val mergeOptions = params.getOrElse("mergeOptions", Nil)
        if (mergeOptions.isEmpty) {
          Seq("mergeOptions" -> "At least one option must be enabled.")
        } else if (!mergeOptions.forall(x => Seq("merge-commit", "squash", "rebase").contains(x))) {
          Seq("mergeOptions" -> "mergeOptions are invalid.")
        } else {
          Nil
        }
      }
    }

}
