'use strict';
// Read-only Gmail, Calendar and Drive answers for explicit user commands.
const GOOGLE = require('./google-oauth');

// "show my emails", "what's on my calendar today", "my drive files", ...
const GOOGLE_INTENT = {
  gmail: /\b(?:my|unread|latest|recent|new)\s+(?:\w+\s+)?(?:e-?mails?|mails?|inbox|gmail)\b|\b(?:check|show|read|open)\s+(?:my\s+)?(?:gmail|inbox)\b/i,
  calendar: /\b(?:my|today'?s|tomorrow'?s)\s+(?:\w+\s+)?(?:calendar|events?|meetings?|schedule|agenda)\b|\bon my calendar\b/i,
  drive: /\b(?:my|recent)\s+(?:\w+\s+)?(?:google\s+)?drive(?:\s+files)?\b|\bmy\s+(?:recent\s+)?files\b/i
};
function detectGoogleIntent(text = '') {
  return Object.keys(GOOGLE_INTENT).find((k) => GOOGLE_INTENT[k].test(text)) || null;
}

async function api(name, url) {
  const token = await GOOGLE.accessToken(name);
  if (!token) return { notConnected: true };
  const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (r.status === 401) return { notConnected: true };
  if (!r.ok) throw new Error(`Google ${name} request failed (${r.status})`);
  return { data: await r.json() };
}
const label = { gmail: 'Gmail', calendar: 'Google Calendar', drive: 'Google Drive' };
const notConnected = (name) => ({ executed: false, skill: name, providerConnected: false,
  message: `${label[name]} is not connected yet. Open the menu → Connectors → ${label[name]} → Connect securely, sign in, then ask again.` });

async function gmail() {
  const list = await api('gmail', 'https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=5&q=in%3Ainbox');
  if (list.notConnected) return notConnected('gmail');
  const ids = (list.data.messages || []).map((m) => m.id);
  if (!ids.length) return { executed: true, skill: 'gmail', message: 'Your inbox is empty.' };
  const rows = await Promise.all(ids.map(async (id) => {
    const m = await api('gmail', `https://gmail.googleapis.com/gmail/v1/users/me/messages/${id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject`);
    const h = Object.fromEntries((m.data?.payload?.headers || []).map((x) => [x.name.toLowerCase(), x.value]));
    const from = String(h.from || 'Unknown').replace(/\s*<[^>]+>/, '').replace(/"/g, '');
    const unread = (m.data?.labelIds || []).includes('UNREAD') ? ' (unread)' : '';
    return `• ${from}: ${h.subject || '(no subject)'}${unread}`;
  }));
  return { executed: true, skill: 'gmail', speak: true, message: `Your latest ${rows.length} emails:\n${rows.join('\n')}` };
}

function istDayRange(text) {
  const offset = 330 * 60000; // India time (UTC+5:30)
  const nowIst = new Date(Date.now() + offset);
  const day = Date.UTC(nowIst.getUTCFullYear(), nowIst.getUTCMonth(), nowIst.getUTCDate()) + (/\btomorrow\b/i.test(text) ? 86400000 : 0);
  return { from: new Date(day - offset), to: new Date(day - offset + 86400000), word: /\btomorrow\b/i.test(text) ? 'tomorrow' : 'today' };
}
async function calendar(text) {
  const { from, to, word } = istDayRange(text);
  const q = new URLSearchParams({ timeMin: from.toISOString(), timeMax: to.toISOString(), singleEvents: 'true', orderBy: 'startTime', maxResults: '10' });
  const r = await api('calendar', `https://www.googleapis.com/calendar/v3/calendars/primary/events?${q}`);
  if (r.notConnected) return notConnected('calendar');
  const items = r.data.items || [];
  if (!items.length) return { executed: true, skill: 'calendar', speak: true, message: `You have no events ${word}.` };
  const time = (e) => e.start?.dateTime ? new Date(e.start.dateTime).toLocaleTimeString('en-IN', { timeZone: 'Asia/Kolkata', hour: 'numeric', minute: '2-digit' }) : 'All day';
  return { executed: true, skill: 'calendar', speak: true, message: `Your events ${word}:\n${items.map((e) => `• ${time(e)} – ${e.summary || '(no title)'}`).join('\n')}` };
}

async function drive() {
  const q = new URLSearchParams({ pageSize: '5', orderBy: 'modifiedTime desc', fields: 'files(name,modifiedTime)', q: 'trashed = false' });
  const r = await api('drive', `https://www.googleapis.com/drive/v3/files?${q}`);
  if (r.notConnected) return notConnected('drive');
  const files = r.data.files || [];
  if (!files.length) return { executed: true, skill: 'drive', message: 'No files found in your Drive.' };
  return { executed: true, skill: 'drive', message: `Your recent Drive files:\n${files.map((f) => `• ${f.name} (${new Date(f.modifiedTime).toLocaleDateString('en-IN')})`).join('\n')}` };
}

async function answerGoogle(name, text) {
  try {
    if (name === 'gmail') return await gmail();
    if (name === 'calendar') return await calendar(text);
    if (name === 'drive') return await drive();
  } catch (e) {
    return { executed: false, skill: name, unavailable: true, message: `${label[name]} did not answer right now. Please try again. Nothing was changed.` };
  }
  return null;
}
module.exports = { detectGoogleIntent, answerGoogle, GOOGLE_INTENT };
