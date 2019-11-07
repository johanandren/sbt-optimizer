/*
 *    Copyright 2016 Johannes Rudolph
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package sbt.executionreporter

import java.util.concurrent.ConcurrentHashMap

import net.virtualvoid.optimizer._
import IvyLockReporter.{ SpentTimeInLock, Listener }
import ExecutionProgressReporter.TaskTiming

import sbt._
import internal.TaskName

import scala.annotation.tailrec

private[sbt] class RichExecutionProgress extends ExecuteProgress[Task] {
  private[this] val calledBy = new ConcurrentHashMap[Task[_], Task[_]]
  private[this] val anonOwners = new ConcurrentHashMap[Task[_], Task[_]]
  private[this] val data = new ConcurrentHashMap[Task[_], TaskTiming]

  override def initial: Unit = {
    data.clear()
    calledBy.clear()
    anonOwners.clear()
  }

  override def afterRegistered(task: Task[_], allDeps: Iterable[Task[_]], pendingDeps: Iterable[Task[_]]): Unit = {
    pendingDeps foreach { t ⇒ if (TaskName.transformNode(t).isEmpty) anonOwners.put(t, task) }
    if (data.containsKey(task))
      updateData(task)(d ⇒ d.copy(task, deps = d.deps ++ allDeps.toSeq, registerTime = theTime()))
    else
      data.put(task, TaskTiming(task, mappedName(task), deps = allDeps.toSeq, registerTime = theTime()))
  }

  override def afterReady(task: Task[_]): Unit =
    updateData(task)(_.copy(readyTime = theTime()))

  override def beforeWork(task: Task[_]): Unit = {
    val listener = new DownloadListener {
      def downloadOccurred(download: NetworkAccess): Unit = updateData(task)(d ⇒ d.copy(downloads = d.downloads :+ download))
    }
    val lockListener = new Listener {
      def spentTimeInLock(spent: SpentTimeInLock): Unit = updateData(task)(d ⇒ d.copy(locks = d.locks :+ spent))
    }
    require(IvyDownloadReporter.listener.get == null)
    IvyDownloadReporter.listener.set(listener)
    IvyLockReporter.listener.set(lockListener)
    updateData(task)(_.copy(startTime = theTime(), threadId = Thread.currentThread().getId))
  }

  def afterWork[T](task: Task[T], result: Either[Task[T], Result[T]]): Unit = {
    IvyDownloadReporter.listener.set(null)
    IvyLockReporter.listener.set(null)
    updateData(task)(_.copy(finishTime = theTime()))
    result.left.foreach { t ⇒
      calledBy.put(t, task)
      data.put(t, TaskTiming(t, mappedName(t), deps = Seq(task)))
    }
  }

  override def afterCompleted[A](task: Task[A], result: Result[A]): Unit = updateData(task)(_.copy(completeTime = theTime()))

  override def afterAllCompleted(results: RMap[Task, Result]): Unit =
    ExecutionProgressReporter.report(data)

  override def stop(): Unit = ()

  def theTime(): Option[Long] = Some(System.nanoTime())

  @tailrec
  private[this] def updateData(task: Task[_])(f: TaskTiming ⇒ TaskTiming): Unit = {
    val old = data.get(task)
    val newValue = f(old)
    if (!data.replace(task, old, newValue)) updateData(task)(f)
  }

  private[this] def inferredName(t: Task[_]): Option[String] =
    Option(anonOwners.get(t)).map(t ⇒ mappedName(t) + "<anon>") orElse
      Option(calledBy.get(t)).map(t ⇒ mappedName(t) + "<called-by>")
  var cache = Map.empty[Task[_], String]
  private[this] def mappedName(t: Task[_]): String = {
    cache.get(t) match {
      case Some(name) ⇒ name
      case None ⇒
        cache = cache.updated(t, "<cyclic>")
        val name = mappedNameImpl(t)
        cache = cache.updated(t, name)
        name
    }
  }
  private[this] def mappedNameImpl(t: Task[_]): String = TaskName.definedName(t) orElse inferredName(t) getOrElse TaskName.anonymousName(t)
}

object RichExecutionProgress {
  val install: sbt.Def.Setting[_] = Keys.progressReports :=
    Keys.progressReports.value :+ new Keys.TaskProgress(new RichExecutionProgress)
}
