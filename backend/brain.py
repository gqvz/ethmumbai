"""Decision making on 0G Compute. If 0G is unreachable the result is explicit 'degraded' - never a made-up decision."""
import json, requests
import config

SYSTEM = """You are the decision brain of a wearable assistant. Input: a speech transcript and BLE beacon state.
Reply with strict JSON: {"summary": str, "notify": bool, "notify_text": str, "reason": str}.
notify=true only for things the user must act on (a reminder they asked for, an item left behind, urgent info)."""

def decide(transcript: str, ble_state: dict):
    if not config.OG_KEY:
        return {"degraded": True, "reason": "OG_API_KEY not set", "notify": False}
    try:
        r = config.post(
            f"{config.OG_BASE}/chat/completions",
            headers={"Authorization": f"Bearer {config.OG_KEY}"},
            json={"model": config.OG_CHAT_MODEL, "temperature": 0, "chat_template_kwargs": {"enable_thinking": False},
                  "messages": [{"role": "system", "content": SYSTEM},
                               {"role": "user", "content": json.dumps({"transcript": transcript, "ble": ble_state})}]},
            timeout=60)
        r.raise_for_status()
        txt = r.json()["choices"][0]["message"]["content"] or ""
        out = json.loads(txt[txt.index("{"):txt.rindex("}") + 1])
        out["request_id"] = r.headers.get("x-request-id")
        return out
    except Exception as e:  # safe-stop: no action, flagged
        return {"degraded": True, "reason": f"0g_unreachable: {e}", "notify": False}


def _chat(system: str, user: str, as_json: bool = False, max_tokens: int = 600):
    """One 0G chat call (thinking off). Raises on failure - callers decide how to degrade (never fabricate)."""
    if not config.OG_KEY:
        raise RuntimeError("OG_API_KEY not set")
    r = config.post(
        f"{config.OG_BASE}/chat/completions",
        headers={"Authorization": f"Bearer {config.OG_KEY}"},
        json={"model": config.OG_CHAT_MODEL, "temperature": 0, "max_tokens": max_tokens,
              "chat_template_kwargs": {"enable_thinking": False},
              "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]},
        timeout=60)
    r.raise_for_status()
    txt = (r.json()["choices"][0]["message"]["content"] or "").strip()
    rid = r.headers.get("x-request-id")
    if as_json:
        return json.loads(txt[txt.index("{"):txt.rindex("}") + 1]), rid
    return txt, rid

COMMAND_SYS = """You parse one spoken command for a wearable assistant (speech-to-text may be slightly garbled).
Reply with strict JSON only: {"intent": "summarize_last"|"session_start"|"search"|"remind_time"|"remind_beacon"|"cancel_reminder"|"transcript"|"status"|"export"|"hardware"|"none",
"query": str, "date_from": "YYYY-MM-DD"|null, "date_to": "YYYY-MM-DD"|null, "due": "YYYY-MM-DDTHH:MM"|null,
"message": str, "about_last_conversation": bool, "beacon_trigger": "lost"|"found"|null, "beacon_label": str|null,
"actions": [{"do": "servo"|"buzz"|"led"|"sos", "value": str, "repeat": int}],
"also": [{"intent": "remind_time"|"remind_beacon", "due": str|null, "message": str, "beacon_trigger": "lost"|"found"|null, "beacon_label": str|null, "actions": [{"do": str, "value": str}]}]}
- summarize_last: summarize the conversation that just ended.
- session_start: user is about to have a conversation and wants it summarized when it ends.
- search: user asks what was said/discussed earlier (query = topic, date_from/date_to = range if a time like 'last week' is given).
- remind_time: reminder at a time. due = local time; 'next week' = same weekday next week 09:00, 'tomorrow' = 09:00, 'in N minutes' computed from now.
- remind_beacon: remind when a tracked item/beacon goes out of range (trigger 'lost') or comes back ('found'); message = what to say.
- cancel_reminder: user wants to remove/delete/cancel a reminder; query = what it was about (empty if they mean the latest one), message = "all" if they want every reminder removed.
- transcript: user asks for the full transcript / everything that was said (date_from/date_to if a day or range is given, otherwise the last conversation; message = "all" if they want everything).
- status: user asks about device state: Bluetooth / beacon / ESP32s / mics / what is connected / is my bag in range / how far is it / what reminders are pending.
- export: user asks to save / export / share / back up / seal / store the conversation (on 0G or anywhere).
- hardware: do something with the devices right now (no condition). Put each in actions.
- about_last_conversation: true if the reminder refers to 'this'/'that'/the last conversation.
- actions: hardware to use, IN THE ORDER THEY MUST HAPPEN (a sequence: "close the door then buzz then open it then buzz twice" = servo close, buzz, servo open, buzz with repeat 2). "twice"/"three times" = repeat. "sos" = the SOS morse signal on LED + buzzer (value ""). buzz value "beep" is a short blip, "jingle" a tune, "alarm" a siren. servo = the door/latch/needle (value "open", "close", "sweep", a number of degrees, or "gauge" to release it), buzz = the buzzer (value "beep" or "alarm"), led = the light (value "flash" or "alert").
  For remind_time and remind_beacon the actions run WHEN THE REMINDER FIRES, so "open the door when my headphones go out of range" = remind_beacon with beacon_label of the headphones, trigger "lost", actions [{"do":"servo","value":"open"}].
  "...and remind me" with nothing more: message = a short helpful line about the trigger (e.g. "Your headphones went out of range."). Any intent may also carry actions to run now (e.g. "summarize that and beep").
- also: a COMPOSITE command has an immediate part and one or more deferred parts ("close the door and open it again when my headphones are back in range", "beep now and again at 9pm").
  Put the immediate part in intent + actions, and EACH deferred part in "also" (its own trigger: beacon_label/beacon_trigger or due, its own actions, message = short line saying what happened, e.g. "Headphones are back, door reopened."). "also" is [] when there is nothing deferred.
  "back in range" / "comes back" / "returns" = trigger "found"; "leave" / "out of range" / "disconnect" = trigger "lost". "open it again" = servo open, "close it again" = servo close.
- Examples: "close the door then buzz then open it then buzz twice" -> hardware, actions [servo close, buzz beep, servo open, buzz beep repeat 2]. "SOS" or "send an SOS" -> hardware, actions [sos]. "close the room door and open it again when my headphones are back in range" -> intent hardware, actions [servo close], also [{remind_beacon, found, headphones label, actions [servo open]}]. "close the room door" -> hardware, actions servo close. "buzz when my bag leaves and close the door" -> remind_beacon lost (the bag beacon), actions buzz alarm + servo close.
- If it is not a clear command, intent = "none"."""

def command(text: str, now: str, beacons: list):
    return _chat(COMMAND_SYS, json.dumps({"now": now, "known_beacons": beacons, "speech": text}), as_json=True)

def _plain(t):
    """The app shows plain text: drop markdown emphasis and turn list stars into dashes."""
    import re
    if isinstance(t, tuple):
        return (_plain(t[0]),) + tuple(t[1:])
    t = re.sub(r"\*\*(.+?)\*\*", r"\1", t)
    return re.sub(r"(?m)^\s*[\*\-]\s+", "– ", t).strip()

def summarize(convo_text: str):
    return _plain(_chat("Summarize this conversation transcript in 2-4 short bullet points (decisions, names, action items). "
                 "Transcripts may be noisy; do not invent details. Plain text only, no markdown.", convo_text, max_tokens=400))

def answer(question: str, snippets: str):
    return _chat("Answer the user's question using only these timestamped transcript snippets and summaries of their past conversations. "
                 "Be brief (1-3 sentences) and mention when it was said. If nothing relevant is there, say you could not find it.",
                 f"Question: {question}\n\nSnippets:\n{snippets}", max_tokens=300)

def write_reminder(message: str, context: str):
    return _chat("Write one short reminder notification (max 25 words) from the request and context. Plain text, no \"Reminder:\" prefix.",
                 f"Request: {message}\nContext: {context}", max_tokens=100)


COMMIT_SYS = """From this conversation transcript, list things a speaker committed to doing later ("I'll", "we need to", "I should", "remind"...).
Reply with strict JSON only: {"items": [{"text": str, "due": "YYYY-MM-DDTHH:MM"|null}]}. text = short imperative (max 12 words, e.g. "Follow up with Rohan about extension cords").
due only if a time was clearly said (relative to "now"), else null. Max 3 items. Empty list if there is no clear commitment. Never invent."""

def commitments(convo_text: str, now: str):
    return _chat(COMMIT_SYS, json.dumps({"now": now, "transcript": convo_text}), as_json=True)
