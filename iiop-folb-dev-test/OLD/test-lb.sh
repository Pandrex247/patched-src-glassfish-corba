. ../cluster.props
APS_HOME="test/OrbFailOver/build"
S1AS_HOME="${instance1_s1as_home}/glassfish"
ASADMIN="${S1AS_HOME}/bin/asadmin"
APPCLIENT="${S1AS_HOME}/bin/appclient"
PROPS_FILE=${PWD}/logging.properties

# HOST_PORTS=${instance1_node}:${instance1_IIOP_LISTENER_PORT},${instance2_node}:${instance2_IIOP_LISTENER_PORT},${instance3_node}:${instance3_IIOP_LISTENER_PORT} 
HOST_PORTS=${instance1_node}:${instance1_IIOP_LISTENER_PORT},${instance2_node}:${instance2_IIOP_LISTENER_PORT}

set -x
${ASADMIN} deploy --target ${cluster_name} --force ${APS_HOME}/OrbFailOver-ejb.jar

#${APPCLIENT} -Djavax.enterprise.resource.corba.level=FINE -Djava.util.loggin.level=FINE -client  ${APS_HOME}/OrbFailOver-app-client.jar -targetserver ${instance1_node}:${instance1_IIOP_LISTENER_PORT},${instance2_node}:${instance2_IIOP_LISTENER_PORT} -name OrbFailOver-app-client &

${APPCLIENT} -Djava.util.logging.config.file=${PROPS_FILE} -client  ${APS_HOME}/OrbFailOver-app-client.jar -targetserver ${HOST_PORTS} -name OrbFailOver-app-client -loadbalance 
