'use strict';

const form = document.querySelector('#family-form');
const input = document.querySelector('#family-link');
const error = document.querySelector('#family-error');
const idPattern = '[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}';
const directId = new RegExp(`^${idPattern}$`, 'i');
const familyPath = new RegExp(`^/f/(${idPattern})/?$`, 'i');

function familyId(value) {
  if (directId.test(value)) return value;
  if (/^[0-9a-f]{32}$/i.test(value)) return value.replace(/^(........)(....)(....)(....)/, '$1-$2-$3-$4-');
  try {
    const url = new URL(value, location.origin);
    if (url.origin !== location.origin || url.username || url.password) return null;
    return url.pathname.match(familyPath)?.[1] ?? null;
  } catch {
    return null;
  }
}

form.addEventListener('submit', event => {
  event.preventDefault();
  const id = familyId(input.value.trim());
  if (id) {
    location.assign(`/f/${id.toLowerCase()}?lang=${trackerI18n.language}`);
    return;
  }
  input.setAttribute('aria-invalid', 'true');
  error.hidden = false;
  input.focus();
});

input.addEventListener('input', () => {
  input.removeAttribute('aria-invalid');
  error.hidden = true;
});
