package org.querki.facades

import org.querki.jquery.JQuery

package object manifest {
  implicit def jq2MarcoPolo(jq: JQuery): MarcoPoloFacade = jq.asInstanceOf[MarcoPoloFacade]

  implicit def jq2Manifest(jq: JQuery): ManifestFacade = jq.asInstanceOf[ManifestFacade]
  implicit def manifest2Commands(manifest: ManifestFacade) = new ManifestCommands(manifest)
  implicit def jq2ManifestCommands(jq: JQuery) = new ManifestCommands(jq.asInstanceOf[ManifestFacade])
}
