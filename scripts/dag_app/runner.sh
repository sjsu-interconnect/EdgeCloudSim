#!/bin/sh

# Portable script root (avoid readlink -f which may not exist on macOS)
script_root_path="$(cd "$(dirname "$0")" && pwd)"

simulation_out_folder=$1
scenario_name=$2
edge_devices_file=$3
applications_file=$4
iteration_number=$5

# Validate arguments
if [ -z "$simulation_out_folder" ] || [ -z "$scenario_name" ] || [ -z "$edge_devices_file" ] || [ -z "$applications_file" ] || [ -z "$iteration_number" ]; then
    echo "Usage: $0 <simulation_out_folder> <scenario_name> <edge_devices_file> <applications_file> <iteration_number>"
    echo "Example: $0 ../../scripts/output DAG_APP edge_devices.xml applications.xml 1"
    exit 1
fi

scenario_out_folder=${simulation_out_folder}/${scenario_name}/ite${iteration_number}
scenario_conf_file=${script_root_path}/config/${scenario_name}.properties
scenario_edge_devices_file=${script_root_path}/config/${edge_devices_file}
scenario_applications_file=${script_root_path}/config/${applications_file}

# Fallback to default_config.properties if scenario-specific properties don't exist
if [ ! -f "$scenario_conf_file" ]; then
    scenario_conf_file=${script_root_path}/config/default_config.properties
    echo "Note: Using default_config.properties instead of ${scenario_name}.properties"
fi

mkdir -p $scenario_out_folder
java -classpath '../../bin:../../lib/cloudsim-7.0.0-alpha.jar:../../lib/commons-math3-3.6.1.jar:../../lib/colt.jar:../../lib/gson-2.10.1.jar' edu.boun.edgecloudsim.applications.sample_app1.MainApp $scenario_conf_file $scenario_edge_devices_file $scenario_applications_file $scenario_out_folder $iteration_number > ${scenario_out_folder}.log

if [ $? -eq 0 ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') - ite${iteration_number} OK" >> ${simulation_out_folder}/${scenario_name}/progress.log
else
    echo "$(date '+%Y-%m-%d %H:%M:%S') - ite${iteration_number} FAIL !!!" >> ${simulation_out_folder}/${scenario_name}/progress.log
fi

tar -czf ${scenario_out_folder}.tar.gz -C $simulation_out_folder/${scenario_name} ite${iteration_number}
rm -rf $scenario_out_folder
