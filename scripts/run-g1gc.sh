#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# run-g1gc.sh  —  Launch GC Visualizer with G1GC
#
# Profiles (based on your available RAM):
#   --profile low     -Xmx512m  (8GB machine, IDE open)
#   --profile medium  -Xmx1g    (8GB machine, comfortable)
#   --profile high    -Xmx2g    (16GB machine)
#   --profile ultra   -Xmx4g    (32GB machine or EC2 t3.large)
# ─────────────────────────────────────────────────────────────────────────────

PROFILE=${1:-medium}
JAR="target/gc-visualizer-1.0.0.jar"

if [ ! -f "$JAR" ]; then
  echo "❌ JAR not found. Run: ./mvnw clean package -DskipTests"
  exit 1
fi

mkdir -p logs

case $PROFILE in
  low)    XMX="-Xms256m -Xmx512m" ;;
  medium) XMX="-Xms512m -Xmx1g"   ;;
  high)   XMX="-Xms1g   -Xmx2g"   ;;
  ultra)  XMX="-Xms2g   -Xmx4g"   ;;
  *)
    echo "Unknown profile: $PROFILE. Use: low | medium | high | ultra"
    exit 1
    ;;
esac

echo ""
echo "  ██████╗  ██╗     ██████╗  ██████╗"
echo " ██╔════╝  ╚██╗   ██╔════╝ ██╔════╝"
echo " ██║  ███╗  ╚██╗  ██║  ███╗██║     "
echo " ██║   ██║   ██╔╝ ██║   ██║██║     "
echo " ╚██████╔╝  ██╔╝  ╚██████╔╝╚██████╗"
echo "  ╚═════╝  ╚═╝    ╚═════╝  ╚═════╝ "
echo ""
echo " GC: G1GC  |  Profile: $PROFILE  |  Heap: $XMX"
echo " Dashboard: http://localhost:8080"
echo " GC Log:    logs/g1gc-$PROFILE.log"
echo ""

java \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  $XMX \
  -Xlog:gc*:file=logs/g1gc-$PROFILE.log:time,uptime,level,tags \
  -XX:+PrintGCDetails \
  -jar "$JAR"
