#!/system/bin/sh
# Start Petals server for distributed LLM inference
# Model is passed via --model argument

MODEL=""
PORT="50052"

while [ $# -gt 0 ]; do
    case "$1" in
        --model)
            MODEL="$2"
            shift 2
            ;;
        --port)
            PORT="$2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

if [ -z "$MODEL" ]; then
    echo "ERROR: --model required"
    exit 1
fi

LOG_FILE="/data/data/com.akinus21.akaiagent/files/petals.log"

echo "Starting Petals for model: $MODEL" | tee -a "$LOG_FILE"

# Check if Python3 is available
if ! command -v python3 > /dev/null 2>&1; then
    echo "ERROR: python3 not found" | tee -a "$LOG_FILE"
    exit 1
fi

# Check if Petals is installed
if ! python3 -m pip show petals > /dev/null 2>&1; then
    echo "Installing Petals..." | tee -a "$LOG_FILE"
    pip install --user git+https://github.com/bigscience-workshop/petals >> "$LOG_FILE" 2>&1
    if [ $? -ne 0 ]; then
        echo "ERROR: Petals installation failed" | tee -a "$LOG_FILE"
        exit 1
    fi
fi

# Start Petals server
echo "Starting Petals server on port $PORT..." | tee -a "$LOG_FILE"
exec python3 -m petals.cli.run_server "$MODEL" --port "$PORT" >> "$LOG_FILE" 2>&1