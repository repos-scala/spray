/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray

import http._
import StatusCodes._
import akka.actor.{Actor, ActorRef}
import akka.dispatch.{Future, Futures}
import utils.{PostStart, Logging}

/**
 * The RootService actor is the central entrypoint for HTTP requests entering the ''spray'' infrastructure.
 * It is responsible for creating an [[cc.spray.http.HttpRequest]] object for the request as well as dispatching this
 *  [[cc.spray.http.HttpRequest]] object to all attached [[cc.spray.HttpService]]s. 
 */
class RootService extends Actor with ToFromRawConverter with Logging with PostStart {
  private var services: List[ActorRef] = Nil
  private var handler: RawRequestContext => Unit = handleNoServices

  self.id = SpraySettings.RootActorId

  lazy val addConnectionCloseResponseHeader = SpraySettings.CloseConnection

  override def preStart() {
    log.debug("Starting spray RootService ...")
    super.preStart()
  }

  def postStart() {
    cc.spray.http.warmUp()
    log.info("spray RootService started")
  }

  override def postStop() {
    log.info("spray RootService stopped")
  }

  override def preRestart(reason: Throwable) {
    log.info("Restarting spray RootService because of previous %s ...", reason.getClass.getName)
  }

  override def postRestart(reason: Throwable) {
    log.info("spray RootService restarted");
  }

  protected def receive = {
    case rawContext: RawRequestContext => {
      try {
        handler(rawContext)
      } catch {
        case e: Exception => handleException(e, rawContext)
      }
    }
    case Attach(service) => attach(service)
    case Detach(service) => detach(service)
  }
  
  private def handleNoServices(rawContext: RawRequestContext) {
    log.debug("Received %s with no attached services, completing with 404", toSprayRequest(rawContext.request))
    completeNoService(rawContext)
  }
  
  private def handleOneService(rawContext: RawRequestContext) {
    val request = toSprayRequest(rawContext.request)
    log.debug("Received %s with one attached service, dispatching...", request)
    (services.head !!! (request, SpraySettings.AsyncTimeout)).onComplete(completeRequest(rawContext) _)
  }
  
  private def handleMultipleServices(rawContext: RawRequestContext) {    
    val request = toSprayRequest(rawContext.request)
    log.debug("Received %s with %s attached services, dispatching...", request, services.size)
    val serviceFutures: List[Future[Option[HttpResponse]]] = services.map(_ !!! (request, SpraySettings.AsyncTimeout))
    val resultsFuture = Futures.fold(None.asInstanceOf[Option[HttpResponse]], SpraySettings.AsyncTimeout)(serviceFutures) {
      case (None, None) => None
      case (None, x: Some[_]) => x
      case (x: Some[_], None) => x
      case (x: Some[_], Some(y)) =>
        log.warn("Received a second response for request '%s':\n\nn%s\n\nIgnoring the additional response...", request, y)
        x
    }
    resultsFuture.onComplete(completeRequest(rawContext) _)
  }
  
  private def completeNoService(rawContext: RawRequestContext) {
    rawContext.complete(fromSprayResponse(noService(rawContext.request.uri)))
  }
  
  private def completeRequest(rawContext: RawRequestContext)(future: Future[Option[HttpResponse]]) {
    if (future.exception.isEmpty) {
      future.result.get match {
        case Some(response) => rawContext.complete(fromSprayResponse(response))
        case None => completeNoService(rawContext)
      }  
    } else {
      handleException(future.exception.get, rawContext)
    }
  }

  protected def handleException(e: Throwable, rawContext: RawRequestContext) {
    log.error(e, "Exception during request processing")
    rawContext.complete(fromSprayResponse(e match {
      case e: HttpException => HttpResponse(e.failure)
      case e: Exception => HttpResponse(InternalServerError, e.getMessage)
    }))
  }

  protected def attach(serviceActor: ActorRef) {
    services = serviceActor :: services
    if (serviceActor.isUnstarted) serviceActor.start()
    updateHandler()
  }
  
  protected def detach(serviceActor: ActorRef) {
    services = services.filter(_ != serviceActor)    
    updateHandler()
  }
  
  private def updateHandler() {
    handler = services.size match {
      case 0 => handleNoServices
      case 1 => handleOneService
      case _ => handleMultipleServices
    }
  }
  
  protected def noService(uri: String) = HttpResponse(404, "No service available for [" + uri + "]")
}

case class Attach(serviceActorRef: ActorRef)

case class Detach(serviceActorRef: ActorRef)

object Attach {
  def apply(serviceActor: => Actor): Attach = apply(Actor.actorOf(serviceActor))
}