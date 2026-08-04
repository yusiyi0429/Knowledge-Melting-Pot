FROM python:3.13-alpine

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /opt/kmp
COPY deploy/sandbox/skill_sandbox.py /opt/kmp/skill_sandbox.py

USER 65532:65532
EXPOSE 8081
CMD ["python", "-I", "/opt/kmp/skill_sandbox.py"]
