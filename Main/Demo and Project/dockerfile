# Use the official Ubuntu as the base image
FROM ubuntu:latest

COPY *.c    /root
COPY *.py   /root
COPY *.html /root

# Update the package lists, install essential packages, and clean up
RUN apt update \
    && apt install -y sudo gcc python3 wget nano p7zip p7zip-full zip unzip  \
    && rm -rf /var/lib/apt/lists/*

# Add the current directory (.) to the PATH environment variable
ENV PATH="${PATH}:."

# Set the working directory
WORKDIR /root

# Start a Bash shell when the container is run
CMD ["/bin/bash"]