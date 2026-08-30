async function test() {
  try {
    const res = await fetch('https://zoom-ai-participent.onrender.com/api/bot/record', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        meetingUrl: 'https://zoom.us/j/89123456789?pwd=test',
        displayName: 'Cloud Bot'
      })
    });
    console.log('HTTP STATUS:', res.status);
    const data = await res.json();
    console.log('RESPONSE DATA:', JSON.stringify(data, null, 2));
  } catch (err) {
    console.error('ERROR:', err.message);
  }
}

test();
