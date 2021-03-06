/*
 * Copyright (c) 2019 - Convergence Labs, Inc.
 *
 * This file is part of the Convergence Server, which is released under
 * the terms of the GNU General Public License version 3 (GPLv3). A copy
 * of the GPLv3 should have been provided along with this file, typically
 * located in the "LICENSE" file, which is part of this source code package.
 * Alternatively, see <https://www.gnu.org/licenses/gpl-3.0.html> for the
 * full text of the GPLv3 license, if it was not provided.
 */

package com.convergencelabs.convergence.server.util

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

/**
 * TryWithResource implements an idiomatic Scala version of the Java 7
 * try-with-resources control structure.  The apply method takes two
 * argument lists.  The first one is a call-by-name method that produces an
 * object that is an instance of [[java.lang.AutoCloseable]].  The second
 * parameter list takes a method that accepts an instance of AutoCloseable
 * and produces an instance of B.  The value passed into the first argument
 * will be called by name, and passed into the method passed into the second.
 */
object TryWithResource {
  def apply[A <: AutoCloseable, B](resource: => A)(block: A => B): Try[B] =
    new TryWithResource(resource).tryWithResource(block)
}

class TryWithResource[A <: AutoCloseable](r: => A) {
  // scalastyle:off null

  private def tryWithResource[B](block: A => B): Try[B] = {
    // This outer try catches the case where we can't get the resource
    // and returns a Failure with the exception.
    try {
      val resource: A = r
      // Once the resource is resolved, then actually try
      // the code block that was passed in.
      tryWithResolvedResoruce(resource, block)
    } catch {
      case NonFatal(e) => Failure(e)
    }
  }

  /**
   * This method is called once the resource is resolved and implements the
   * bulk of the try-with-resources logic.
   */
  private[this] def tryWithResolvedResoruce[B](resource: A, block: A => B): Try[B] = {
    var result: Try[B] = null
    var t: Throwable = null
    try {
      result = Success(block(resource))
    } catch {
      case NonFatal(e) => {
        t = e
        result = Failure(e)
      }
    } finally {
      if (resource != null) {
        if (t != null) {
          try {
            resource.close()
          } catch {
            case NonFatal(e) => t.addSuppressed(e)
          }
        } else {
          try {
            resource.close()
          } catch {
            case NonFatal(e) => result = Failure(e)
          }
        }
      }
    }
    result
  }
}
