import React, { useState, useEffect, useRef } from 'react';
import { 
  Settings, 
  CreditCard, 
  Play, 
  RefreshCw, 
  CheckCircle, 
  AlertCircle, 
  Lock, 
  Info,
  Layers,
  Send,
  ShieldCheck,
  RotateCcw
} from 'lucide-react';

interface Config {
  baseUrl: string;
  orchestratorUrl: string;
  merchantId: string;
  signingKey: string;
  apiKey: string;
}

interface LogLine {
  timestamp: string;
  type: 'info' | 'success' | 'warn' | 'error' | 'muted';
  text: string;
}

interface TxStatus {
  transactionId: string;
  hasPaymentIntent: boolean;
  hasWebhookReceived: boolean;
  status: 'PENDING' | 'CONVERGED_VERIFIED' | 'CONVERGED_FAILED' | 'UNKNOWN';
}

// UUID helper
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

// Dynamic HMAC SHA256 using browser Web Crypto API
async function calculateHmacSha256(message: string, secret: string): Promise<string> {
  const encoder = new TextEncoder();
  const keyData = encoder.encode(secret);
  const messageData = encoder.encode(message);
  
  const cryptoKey = await window.crypto.subtle.importKey(
    "raw",
    keyData,
    { name: "HMAC", hash: { name: "SHA-256" } },
    false,
    ["sign"]
  );
  
  const signature = await window.crypto.subtle.sign(
    "HMAC",
    cryptoKey,
    messageData
  );
  
  return Array.from(new Uint8Array(signature))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

export default function App() {
  // Config state
  const [config, setConfig] = useState<Config>({
    baseUrl: 'http://localhost:8081',
    orchestratorUrl: 'http://localhost:8082',
    merchantId: 'default-merchant',
    signingKey: 'test-secret-key-123',
    apiKey: 'default-api-key-111'
  });

  // Transaction state
  const [txId, setTxId] = useState<string>('');
  const [amount, setAmount] = useState<number>(1500.00);
  const [currency, setCurrency] = useState<string>('ARS');
  const [webhookStatus, setWebhookStatus] = useState<string>('SUCCESS');

  // Runtime monitor states
  const [status, setStatus] = useState<TxStatus>({
    transactionId: '',
    hasPaymentIntent: false,
    hasWebhookReceived: false,
    status: 'UNKNOWN'
  });
  const [isPolling, setIsPolling] = useState<boolean>(false);
  const [activeStep, setActiveStep] = useState<number>(0);
  const [logs, setLogs] = useState<LogLine[]>([]);

  const consoleEndRef = useRef<HTMLDivElement>(null);
  const pollingTimerRef = useRef<any>(null);

  // Load configuration from localStorage on mount
  useEffect(() => {
    const saved = localStorage.getItem('aegisgate.config');
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        setConfig(prev => ({ ...prev, ...parsed }));
        addLog('info', 'Loaded configurations from localStorage.');
      } catch (e) {
        addLog('warn', 'Failed to parse saved config from localStorage.');
      }
    } else {
      addLog('info', 'No saved configuration found. Using default local profiles.');
    }
    generateNewTxId();
  }, []);

  // Auto-scroll logs
  useEffect(() => {
    consoleEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  // Log handler
  const addLog = (type: LogLine['type'], text: string) => {
    const now = new Date();
    const timestamp = now.toTimeString().split(' ')[0] + '.' + String(now.getMilliseconds()).padStart(3, '0');
    setLogs(prev => [...prev, { timestamp, type, text }]);
  };

  const clearLogs = () => {
    setLogs([]);
    addLog('info', 'Console logs cleared.');
  };

  const generateNewTxId = () => {
    const newId = uuidv4();
    setTxId(newId);
    setStatus({
      transactionId: newId,
      hasPaymentIntent: false,
      hasWebhookReceived: false,
      status: 'UNKNOWN'
    });
    setActiveStep(0);
    addLog('info', `Generated new Transaction UUID: ${newId}`);
  };

  const saveConfig = () => {
    localStorage.setItem('aegisgate.config', JSON.stringify(config));
    addLog('success', 'Merchant configuration successfully saved in localStorage.');
  };

  // Step 1: Register Intent
  const handleRegisterIntent = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!txId) {
      addLog('error', 'Cannot register intent: Transaction ID is empty.');
      return;
    }
    
    addLog('info', `[REST] Initiating checkout intent for transaction ${txId}...`);
    
    try {
      const payload = {
        transaction_id: txId,
        amount: Number(amount),
        currency
      };

      const res = await fetch(`${config.baseUrl}/api/v1/payments/intents`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Merchant-ID': config.merchantId
        },
        body: JSON.stringify(payload)
      });

      if (res.status === 202) {
        addLog('success', `[REST] 202 Accepted. Checkout intent registered.`);
        if (activeStep < 1) setActiveStep(1);
        // Automatically start polling
        startStatusPolling(txId);
      } else {
        const text = await res.text();
        addLog('error', `[REST] Failed with status ${res.status}: ${text}`);
      }
    } catch (err: any) {
      addLog('error', `[REST] Network error calling Ingress intents API: ${err.message}`);
    }
  };

  // Step 2: Simulate Webhook Call
  const handleSendWebhook = async () => {
    if (!txId) {
      addLog('error', 'Cannot send webhook: Transaction ID is empty.');
      return;
    }

    addLog('info', `[REST] Simulating Payway webhook challenge notification...`);

    try {
      // 1. Construct payload
      const webhookPayload = {
        transaction_id: txId,
        status: 'approved',
        verification: {
          status_3ds: webhookStatus,
          eci: '05'
        }
      };
      
      const rawBody = JSON.stringify(webhookPayload);
      
      // 2. Compute signature dynamically using signing key
      addLog('muted', `Calculating HMAC-SHA256 over raw JSON payload...`);
      const signature = await calculateHmacSha256(rawBody, config.signingKey);
      addLog('muted', `Generated signature: ${signature}`);

      // 3. Post to Ingress Webhook endpoint
      const res = await fetch(`${config.baseUrl}/api/v1/gateways/payway/webhooks`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Merchant-ID': config.merchantId,
          'X-Payway-Signature': signature
        },
        body: rawBody
      });

      if (res.status === 202) {
        addLog('success', `[REST] 202 Accepted. Signed webhook received and published.`);
        if (activeStep < 2) setActiveStep(2);
        startStatusPolling(txId);
      } else {
        const text = await res.text();
        addLog('error', `[REST] Webhook rejected with status ${res.status}: ${text}`);
      }
    } catch (err: any) {
      addLog('error', `[REST] Network error calling Webhooks Ingress API: ${err.message}`);
    }
  };

  // Status Polling logic
  const startStatusPolling = (idToPoll: string) => {
    if (pollingTimerRef.current) clearInterval(pollingTimerRef.current);
    
    setIsPolling(true);
    addLog('info', `Started state monitor polling (2s interval)...`);
    
    // Initial fetch
    fetchStatus(idToPoll);
    
    pollingTimerRef.current = setInterval(() => {
      fetchStatus(idToPoll);
    }, 2000);
  };

  const stopStatusPolling = () => {
    if (pollingTimerRef.current) {
      clearInterval(pollingTimerRef.current);
      pollingTimerRef.current = null;
    }
    setIsPolling(false);
    addLog('info', 'State monitor polling stopped.');
  };

  const fetchStatus = async (idToPoll: string) => {
    try {
      const res = await fetch(`${config.orchestratorUrl}/api/v1/payments/${idToPoll}/status`);
      if (res.ok) {
        const data: TxStatus = await res.json();
        setStatus(data);
        
        addLog('muted', `State query: status=${data.status}, intent=${data.hasPaymentIntent}, webhook=${data.hasWebhookReceived}`);
        
        // Update steps accordingly
        if (data.status === 'CONVERGED_VERIFIED') {
          setActiveStep(2);
        } else if (data.status === 'CONVERGED_FAILED') {
          setActiveStep(2);
        }
      } else {
        addLog('warn', `State query returned non-200 status: ${res.status}`);
      }
    } catch (err: any) {
      addLog('error', `Error polling Orchestrator status API: ${err.message}`);
      stopStatusPolling();
    }
  };

  // Step 3: Authorize & Consume
  const handleAuthorize = async () => {
    if (!txId) {
      addLog('error', 'Cannot authorize: Transaction ID is empty.');
      return;
    }

    addLog('info', `[REST] Executing Verify & Consume checkout authorization checkpoint...`);

    try {
      const res = await fetch(`${config.orchestratorUrl}/api/v1/payments/${txId}/authorize`, {
        method: 'POST'
      });

      const data = await res.json();

      if (res.ok) {
        addLog('success', `[REST] 200 OK. Transaction Authorized successfully! Code: ${data.status}`);
        setActiveStep(3);
        // Refresh status
        fetchStatus(txId);
        stopStatusPolling();
      } else {
        addLog('error', `[REST] ${res.status} Access Denied! Error Code: ${data.errorCode || 'UNKNOWN'} - ${data.message || 'Verification failed'}`);
      }
    } catch (err: any) {
      addLog('error', `[REST] Network error calling Orchestrator authorize API: ${err.message}`);
    }
  };

  // Cleanup polling on unmount
  useEffect(() => {
    return () => {
      if (pollingTimerRef.current) clearInterval(pollingTimerRef.current);
    };
  }, []);

  return (
    <div className="app-container">
      {/* HEADER */}
      <div className="header">
        <div>
          <h1>AegisGate 3DS Sandbox</h1>
          <p style={{ margin: '4px 0 0', fontSize: '0.95rem' }}>Decoupled Microservice Validation & Token State Convergence Monitor</p>
        </div>
        <div className="header-badge">
          <span className="badge-dot"></span>
          <span>Docker Core Services Active</span>
        </div>
      </div>

      {/* CORE LAYOUT */}
      <div className="main-grid">
        
        {/* LEFT COLUMN: CONFIGURATION & INTENT REGISTRATION */}
        <div className="left-panel">
          
          {/* CREDENTIALS CARD */}
          <div className="panel-card">
            <h2 className="card-title">
              <Settings size={20} className="text-accent" style={{ color: 'var(--accent)' }} />
              Credentials Config
            </h2>
            
            <div className="form-group">
              <label>AegisGate Ingress Url</label>
              <input 
                type="text" 
                className="form-input" 
                value={config.baseUrl} 
                onChange={e => setConfig({ ...config, baseUrl: e.target.value })} 
              />
            </div>
            
            <div className="form-group">
              <label>Orchestrator URL</label>
              <input 
                type="text" 
                className="form-input" 
                value={config.orchestratorUrl} 
                onChange={e => setConfig({ ...config, orchestratorUrl: e.target.value })} 
              />
            </div>

            <div className="form-group">
              <label>Merchant ID</label>
              <input 
                type="text" 
                className="form-input" 
                value={config.merchantId} 
                onChange={e => setConfig({ ...config, merchantId: e.target.value })} 
              />
            </div>

            <div className="form-group">
              <label>Payway Signing Key</label>
              <input 
                type="password" 
                className="form-input" 
                value={config.signingKey} 
                onChange={e => setConfig({ ...config, signingKey: e.target.value })} 
              />
            </div>

            <button className="btn btn-secondary" onClick={saveConfig}>
              Save in LocalStorage
            </button>
          </div>

          {/* CHECKOUT INTENT SIMULATOR */}
          <div className="panel-card">
            <h2 className="card-title">
              <CreditCard size={20} style={{ color: 'var(--accent)' }} />
              Checkout Intent
            </h2>

            <form onSubmit={handleRegisterIntent} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <div className="form-group">
                <label>Transaction ID (UUID)</label>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <input 
                    type="text" 
                    className="form-input" 
                    value={txId} 
                    onChange={e => setTxId(e.target.value)} 
                    style={{ fontFamily: 'var(--font-mono)', fontSize: '0.8rem' }}
                  />
                  <button 
                    type="button" 
                    className="btn btn-secondary" 
                    style={{ padding: '0.75rem' }} 
                    onClick={generateNewTxId}
                    title="Generate New ID"
                  >
                    <RotateCcw size={16} />
                  </button>
                </div>
              </div>

              <div className="form-group">
                <label>Amount</label>
                <input 
                  type="number" 
                  step="0.01" 
                  className="form-input" 
                  value={amount} 
                  onChange={e => setAmount(Number(e.target.value))} 
                />
              </div>

              <div className="form-group">
                <label>Currency</label>
                <select 
                  className="form-input" 
                  value={currency} 
                  onChange={e => setCurrency(e.target.value)}
                  style={{ background: '#050608' }}
                >
                  <option value="ARS">ARS (Pesos Argentinos)</option>
                  <option value="USD">USD (Dólares Estadounidenses)</option>
                  <option value="EUR">EUR (Euros)</option>
                </select>
              </div>

              <button type="submit" className="btn">
                <Play size={16} />
                Initiate Checkout Intent
              </button>
            </form>
          </div>

        </div>

        {/* RIGHT COLUMN: EVENTS FLOW, STATUS POLLING, AND CONSOLE LOGS */}
        <div className="right-panel">

          {/* TRANSACTION STEPS FLOW */}
          <div className="panel-card">
            <h2 className="card-title">
              <Layers size={20} style={{ color: 'var(--accent)' }} />
              State Machine Convergence Flow
            </h2>

            <div className="flow-steps">
              {/* Connector line behind */}
              <div className="flow-connector">
                <div 
                  className="flow-connector-active" 
                  style={{ '--percent': `${(activeStep / 3) * 100}%` } as React.CSSProperties}
                ></div>
              </div>

              {/* Step 0: Idle / Generando ID */}
              <div className={`flow-step ${activeStep >= 0 ? (activeStep === 0 ? 'active' : 'completed') : ''}`}>
                <div className="step-icon">
                  <CreditCard size={18} />
                </div>
                <span className="step-label">ID Generated</span>
              </div>

              {/* Step 1: Intent Registered */}
              <div className={`flow-step ${activeStep >= 1 ? (activeStep === 1 ? 'active' : 'completed') : ''}`}>
                <div className="step-icon">
                  <Info size={18} />
                </div>
                <span className="step-label">Intent Registered</span>
              </div>

              {/* Step 2: Webhook Converged */}
              <div className={`flow-step ${activeStep >= 2 ? (activeStep === 2 ? 'active' : 'completed') : ''}`}>
                <div className="step-icon">
                  {status.status === 'CONVERGED_FAILED' ? <AlertCircle size={18} /> : <CheckCircle size={18} />}
                </div>
                <span className="step-label">
                  {status.status === 'CONVERGED_FAILED' ? 'Challenge Failed' : '3DS Verified'}
                </span>
              </div>

              {/* Step 3: Authorized & Consumed */}
              <div className={`flow-step ${activeStep >= 3 ? 'completed' : ''}`}>
                <div className="step-icon">
                  <Lock size={18} />
                </div>
                <span className="step-label">Token Consumed</span>
              </div>
            </div>

            {/* LIVE TRANSACTIONS DETAILS */}
            <div className="status-details-grid">
              <div className="detail-item">
                <span>Transaction Status</span>
                <div>
                  <span className={`status-badge ${status.status.toLowerCase().replace('_', ' ')}`}>
                    {status.status}
                  </span>
                </div>
              </div>
              <div className="detail-item">
                <span>Intent Created</span>
                <span style={{ color: status.hasPaymentIntent ? 'var(--success)' : 'var(--danger)' }}>
                  {status.hasPaymentIntent ? 'TRUE' : 'FALSE'}
                </span>
              </div>
              <div className="detail-item">
                <span>Webhook Received</span>
                <span style={{ color: status.hasWebhookReceived ? 'var(--success)' : 'var(--danger)' }}>
                  {status.hasWebhookReceived ? 'TRUE' : 'FALSE'}
                </span>
              </div>
              <div className="detail-item">
                <span>Active Monitor</span>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  <button 
                    className={`btn btn-secondary ${isPolling ? 'btn-success' : ''}`}
                    onClick={() => isPolling ? stopStatusPolling() : startStatusPolling(txId)}
                    style={{ padding: '0.4rem 0.8rem', fontSize: '0.75rem' }}
                  >
                    <RefreshCw size={12} className={isPolling ? 'spin-anim' : ''} style={{ marginRight: '4px' }} />
                    {isPolling ? 'Polling Active' : 'Start Polling'}
                  </button>
                </div>
              </div>
            </div>

            {/* WEBHOOK SIMULATOR AND AUTHORIZE TRIGGERS */}
            <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', borderTop: '1px solid var(--border)', paddingTop: '1.25rem' }}>
              
              {/* Webhook trigger */}
              <div style={{ flex: '1 1 300px', display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                  <span style={{ fontSize: '0.875rem', fontWeight: '500', color: 'var(--text-light)' }}>3DS Verification Result:</span>
                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.25rem', fontSize: '0.875rem', cursor: 'pointer' }}>
                    <input 
                      type="radio" 
                      name="webhookStatus" 
                      value="SUCCESS" 
                      checked={webhookStatus === 'SUCCESS'} 
                      onChange={() => setWebhookStatus('SUCCESS')} 
                    />
                    SUCCESS
                  </label>
                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: '0.25rem', fontSize: '0.875rem', cursor: 'pointer' }}>
                    <input 
                      type="radio" 
                      name="webhookStatus" 
                      value="FAILED" 
                      checked={webhookStatus === 'FAILED'} 
                      onChange={() => setWebhookStatus('FAILED')} 
                    />
                    FAILED
                  </label>
                </div>
                
                <button 
                  type="button" 
                  className="btn btn-secondary" 
                  onClick={handleSendWebhook}
                  disabled={!txId}
                >
                  <Send size={16} />
                  Simulate Payway Webhook Notification
                </button>
              </div>

              {/* Authorize checkpoint trigger */}
              <div style={{ flex: '1 1 200px', display: 'flex', alignItems: 'flex-end' }}>
                <button 
                  type="button" 
                  className="btn btn-success" 
                  onClick={handleAuthorize}
                  style={{ width: '100%', height: 'calc(100% - 1.5rem)', minHeight: '42px' }}
                  disabled={status.status !== 'CONVERGED_VERIFIED'}
                >
                  <ShieldCheck size={18} />
                  Verify & Consume Checkout Charge
                </button>
              </div>
            </div>
          </div>

          {/* LOGS CONSOLE PANEL */}
          <div className="console-panel">
            <div className="console-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <span className="console-dot red"></span>
                <span className="console-dot yellow"></span>
                <span className="console-dot green"></span>
                <span style={{ marginLeft: '0.5rem', fontWeight: '600' }}>aegisgate-api-logs.log</span>
              </div>
              <div className="console-actions">
                <button 
                  className="btn btn-secondary" 
                  onClick={clearLogs}
                  style={{ padding: '0.25rem 0.5rem', fontSize: '0.7rem', borderRadius: '4px' }}
                >
                  Clear Console
                </button>
              </div>
            </div>
            
            <div className="console-body">
              {logs.length === 0 ? (
                <div style={{ color: '#475569', fontSize: '0.8rem', textAlign: 'center', marginTop: '2rem' }}>
                  -- Console clean. Initiate a transaction to trace events --
                </div>
              ) : (
                logs.map((log, idx) => (
                  <div className="console-line" key={idx}>
                    <span className="line-timestamp">[{log.timestamp}]</span>
                    <span className={`line-text ${log.type}`}>
                      {log.type === 'error' ? '✖ ' : log.type === 'success' ? '✔ ' : ''}
                      {log.text}
                    </span>
                  </div>
                ))
              )}
              <div ref={consoleEndRef} />
            </div>
          </div>

        </div>

      </div>
    </div>
  );
}
