import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

function App() {
  const [status, setStatus] = useState('Not checked');
  const [checking, setChecking] = useState(false);
  async function checkConnection() {
    const base = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');
    if (!base) {
      setStatus('The backend URL has not been configured yet.');
      return;
    }
    setChecking(true);
    try {
      const response = await fetch(`${base}/health`, { signal: AbortSignal.timeout(15000) });
      if (response.status === 429) {
        const seconds = response.headers.get('retry-after') || 'a few';
        setStatus(`Too many requests. Wait ${seconds} seconds, then try again.`);
        return;
      }
      const data = await response.json();
      if (!response.ok || data.status !== 'ok') throw new Error('Unavailable');
      setStatus('Connected to the Java backend.');
    } catch {
      setStatus('Could not reach the backend. Try again shortly.');
    } finally {
      setChecking(false);
    }
  }
  return (
    <main>
      <header><span className="mark">÷</span><span>CSCI 201 · TEAM 3</span><span className="badge">Project starter</span></header>
      <section className="intro">
        <p className="eyebrow">GOOD TIMES. FAIR SHARES.</p>
        <h1>Enjoy the hangout.<br /><span>Split the bill.</span></h1>
        <p className="lede">Our group expense tracker is taking shape. This is the team's starting point for building and testing together.</p>
      </section>
      <section className="cards" aria-label="Planned features">
        <article><span className="number">01</span><h2>Your group</h2><p>Invite friends and keep shared expenses in one place.</p></article>
        <article><span className="number">02</span><h2>Your expenses</h2><p>Enter a bill manually, then add receipt scanning.</p></article>
        <article><span className="number">03</span><h2>Everyone's share</h2><p>See who paid and what each person owes.</p></article>
      </section>
      <section className="connection"><div><h2>Team connection check</h2><p role="status" aria-live="polite">{status}</p></div><button onClick={checkConnection} disabled={checking}>{checking ? 'Connecting…' : 'Check backend'}</button></section>
      <footer>Login, groups, expense storage, and receipt reading are planned features. They are not available in this starter.</footer>
    </main>
  );
}
createRoot(document.getElementById('root')).render(<App />);
