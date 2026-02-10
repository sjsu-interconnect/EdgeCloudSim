#!/bin/sh

# Example driver to run a single iteration for the DAG_APP sample
./compile.sh
./runner.sh ../../scripts/output DAG_APP edge_devices.xml applications.xml 1
