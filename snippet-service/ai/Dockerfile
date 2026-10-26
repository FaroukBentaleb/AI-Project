FROM python:3.12-slim
# CPU PyTorch + transformers + peft: the image serves the fine-tuned adapter in ./model (mounted by compose).
WORKDIR /app
COPY requirements.txt requirements-model.txt ./
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements.txt -r requirements-model.txt
COPY app.py snippet_prompt.py ./
ENV SNIPPET_MODEL_DIR=/app/model
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
