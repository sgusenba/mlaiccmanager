@echo off
cd /d "%~dp0"
mvn clean package exec:java -Dexec.mainClass="com.competition.Main"