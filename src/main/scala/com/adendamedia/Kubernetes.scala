package com.adendamedia

import skuber._
import skuber.json.apps.format._
import skuber.json.ext._
import com.typesafe.config.ConfigFactory
import skuber.apps.StatefulSet
import skuber.apps._
import skuber.ext.Scale.scale
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor._

import skuber.ext._
import skuber.ext._
import skuber.json.ext.format._

import scala.concurrent.Future

object Kubernetes {
  def props = Props(new Kubernetes)

  case object ScaleUp

  val k8s = k8sInit
  private val k8sConfig = ConfigFactory.load().getConfig("kubernetes")
  private val statefulSetName = k8sConfig.getString("statefulset-name")
}

class Kubernetes extends Actor {
  import Kubernetes._
  import scala.concurrent.ExecutionContext.Implicits.global

  def receive = {
    case ScaleUp => scaleUp
  }

  def scaleUp = {

    val resource: Future[StatefulSet] = k8s get[StatefulSet] statefulSetName
    resource map {
      case ss: StatefulSet =>
        println(s"StatefulSet Status: ${ss.status}")
        val currentReplicas = ss.spec.get.replicas
        println(s"Current replicas are ${currentReplicas}")
        val newReplicas = currentReplicas + 2
        println(s"New replicas should be ${newReplicas}")
        println(s"Here's the statefulset resource: ${ss.toString}")
        val newSS = ss.copy(spec = Some(ss.spec.get.copy(replicas = newReplicas)))
        k8s update newSS
    } recoverWith {
      case ex: Throwable =>
        println(s"Oops, couldn't scale the statefulset with error: ${ex.toString}")
        Future(Unit)
    }
  }

}
