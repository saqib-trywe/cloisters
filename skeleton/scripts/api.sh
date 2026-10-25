#!/bin/bash
# usage: api.sh METHOD PATH [JSON] [ACCEPT]
curl -s -w ' [%{http_code}]\n' -X "$1" -H 'Host: localhost:7228' -H 'Content-Type: application/json' -H "Accept: ${4:-application/json}" "http://127.0.0.1:7228$2" ${3:+-d "$3"}
