# 베이스 이미지로 OpenJDK 17 버전을 사용합니다.
FROM eclipse-temurin:17-jdk-jammy

# 빌드된 JAR 파일의 경로를 변수로 지정합니다.
ARG JAR_FILE=build/libs/*.jar

# 위 경로의 JAR 파일을 컨테이너 내부의 app.jar 라는 이름으로 복사합니다.
COPY ${JAR_FILE} app.jar

# 컨테이너가 시작될 때 실행할 명령어를 지정합니다.
ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","/app.jar"]
