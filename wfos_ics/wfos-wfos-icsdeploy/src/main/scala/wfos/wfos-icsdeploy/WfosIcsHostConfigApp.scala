package wfos.wfosicsdeploy

import csw.framework.deploy.hostconfig.HostConfig
import csw.prefix.models.Subsystem

object WfosIcsHostConfigApp {

  def main(args: Array[String]): Unit = {
    HostConfig.start(
      "wfos_ics_host_config_app",
      Subsystem.withNameInsensitive("wfos"),
      args
    )
  }
}
