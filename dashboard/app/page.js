"use client";

import { useEffect, useState } from "react";

const REFRESH_MS = 2000;

export default function DashboardPage() {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);
  const [updatedAt, setUpdatedAt] = useState(null);

  // Poll the status API every 2 seconds while the page is open.
  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const response = await fetch("/api/status", { cache: "no-store" });
        const data = await response.json();
        if (cancelled) return;
        if (!response.ok) {
          setError(data.error || "Request failed");
          return;
        }
        setStatus(data);
        setError(null);
        setUpdatedAt(new Date());
      } catch {
        if (!cancelled) setError("Dashboard server is not reachable");
      }
    }

    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  return (
    <main>
      <header>
        <h1>KV Store Cluster</h1>
        <p className="muted">
          {updatedAt ? `Updated ${updatedAt.toLocaleTimeString()}` : "Loading..."} · refreshes every{" "}
          {REFRESH_MS / 1000}s
        </p>
      </header>

      {error && <div className="error">{error}</div>}

      {status && (
        <>
          <section className="summary">
            <Stat label="Replication factor" value={status.replicationFactor} />
            <Stat label="Write quorum" value={status.writeQuorum} />
            <Stat label="Reads" value={status.router.reads} />
            <Stat label="Writes" value={status.router.writes} />
            <Stat label="Deletes" value={status.router.deletes} />
            <Stat label="Failed requests" value={status.router.failed} />
          </section>

          <section className="nodes">
            {status.nodes.map((node) => (
              <NodeCard key={node.address} node={node} />
            ))}
          </section>
        </>
      )}
    </main>
  );
}

function Stat({ label, value }) {
  return (
    <div className="stat">
      <span className="muted">{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function NodeCard({ node }) {
  const stats = node.stats; // null when the node is DOWN

  return (
    <div className="card">
      <div className="card-header">
        <h2>{node.address}</h2>
        <span className={`badge ${node.status.toLowerCase()}`}>{node.status}</span>
      </div>
      <dl>
        <dt>Keys stored</dt>
        <dd>{stats ? stats.keys : "-"}</dd>
        <dt>Gets / puts / deletes</dt>
        <dd>{stats ? `${stats.gets} / ${stats.puts} / ${stats.deletes}` : "-"}</dd>
        <dt>Pending hints</dt>
        <dd>{node.pendingHints}</dd>
        <dt>Uptime</dt>
        <dd>{stats ? formatUptime(stats.uptimeSeconds) : "-"}</dd>
      </dl>
    </div>
  );
}

function formatUptime(totalSeconds) {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m ${seconds}s`;
}
