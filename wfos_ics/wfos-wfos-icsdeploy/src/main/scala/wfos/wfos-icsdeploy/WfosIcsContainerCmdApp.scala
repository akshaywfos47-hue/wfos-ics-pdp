package wfos.wfosicsdeploy

import csw.framework.deploy.containercmd.ContainerCmd
import csw.prefix.models.Subsystem

object WfosIcsContainerCmdApp {

  def main(args: Array[String]): Unit = {
    ContainerCmd.start(
      "wfos_ics_container_cmd_app",
      Subsystem.withNameInsensitive("wfos"),
      args
    )
  }
}
