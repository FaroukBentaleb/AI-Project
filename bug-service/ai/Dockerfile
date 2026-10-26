FROM python:3.12-slim
# WITH_MODEL=1 adds PyTorch (CPU) + transformers + peft so the trained adapter in ./model can be served.
# Without it the image is tiny and answers with the rule-based fallback.
ARG WITH_MODEL=0
WORKDIR /app
COPY requirements.txt requirements-model.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && if [ "$WITH_MODEL" = "1" ]; then \
         pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
         && pip install --no-cache-dir -r requirements-model.txt; \
       fi
COPY app.py ./
COPY model ./model
ENV TRIAGE_MODEL_DIR=/app/model
EXPOSE 8001
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001"]
