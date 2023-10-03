package com.mchange.sysadmin

import scala.collection.StringOps
import org.apache.commons.text.StringEscapeUtils

opaque type HtmlSafeText = String // to help us avoid double-scaping by accident

extension (s : String)
  def htmlSafeText : HtmlSafeText = StringEscapeUtils.escapeHtml4(s)

extension (hst : HtmlSafeText)
  def nonEmpty : Boolean = StringOps(hst).nonEmpty // disambiguate extension... grrr.
  def isEmpty : Boolean = hst.isEmpty


