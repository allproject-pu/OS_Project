FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive

# ติดตั้ง JDK + locale
RUN apt update && \
    apt install -y openjdk-17-jdk locales && \
    locale-gen en_US.UTF-8 && \
    update-locale LANG=en_US.UTF-8

# ตั้งค่า locale เป็น UTF-8 ในคอนเทนเนอร์
ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8

WORKDIR /app
COPY . /app

# คอมไพล์ไฟล์ Java ทั้งหมดด้วย UTF-8
RUN javac -encoding UTF-8 *.java

EXPOSE 5000

# รันด้วย UTF-8 (กัน output/IO เพี้ยน)
CMD ["java", "-Dfile.encoding=UTF-8", "Server_Os"]
