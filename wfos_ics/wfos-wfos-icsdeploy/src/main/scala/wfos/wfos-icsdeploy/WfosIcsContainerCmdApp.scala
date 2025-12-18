package wfos.wfosicsdeploy

import csw.framework.deploy.containercmd.ContainerCmd
import csw.prefix.models.Subsystem

@main def WfosIcsContainerCmdApp(args: String*): Unit = {
  ContainerCmd.start(
    "wfos_ics_container_cmd_app",
    Subsystem.withNameInsensitive("wfos"),
    args.toArray
  )
}
