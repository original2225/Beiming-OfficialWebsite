# Beiming Backend

This directory contains the P0 Spring Cloud microservice skeleton for the Beiming official website.

## Local prerequisites

- JDK 8 compatible build environment
- Maven 3.8.x
- Local MySQL
- Local Redis
- Local Nacos

## Configuration

Do not commit real passwords. Set local values through environment variables:

```powershell
$env:MYSQL_HOST="127.0.0.1"
$env:MYSQL_PORT="3306"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="<local password>"
$env:REDIS_HOST="127.0.0.1"
$env:NACOS_SERVER_ADDR="127.0.0.1:8848"
```

Create P0 schemas with:

```powershell
mysql -uroot -p < sql/init-p0-schemas.sql
```

## Build

```powershell
mvn clean test
```

## Run examples

```powershell
mvn -pl auth-service spring-boot:run
mvn -pl gateway-service spring-boot:run
```
