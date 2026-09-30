import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { Button, ButtonLink } from '../../src/components/Button';
import { ActionMenu } from '../../src/components/ActionMenu';
import { Icon } from '../../src/components/Icon';
import { Modal } from '../../src/components/Modal';
import { Pagination } from '../../src/components/Pagination';
import '../../src/styles/index.css';

function Fixture() {
  const [confirm, setConfirm] = useState(false);
  const [result, setResult] = useState('No action');
  const [page, setPage] = useState(1);
  const [pending, setPending] = useState(false);
  return <main className="mx-auto max-w-3xl p-6">
    <h1 className="mb-6 text-heading">Shared actions</h1>
    <div className="flex flex-wrap gap-3">
      <Button onClick={() => setResult('Saved')}>Save changes</Button>
      <Button variant="secondary" onClick={() => setResult('Cancelled')}>Cancel</Button>
      <Button variant="tertiary" onClick={() => setResult('Details')}>Show details</Button>
      <ButtonLink to="#destination" variant="tertiary"><Icon name="back" />Back to list</ButtonLink>
      <ActionMenu label="Actions for Table 1" items={[
        { id: 'edit', label: 'Edit', onSelect: () => setResult('Edited') },
        { id: 'disabled', label: 'Reset password', disabled: true, onSelect: () => setResult('WRONG') },
        { id: 'hidden', label: 'Admin only', hidden: true, onSelect: () => setResult('WRONG') },
        { id: 'receipt', label: 'View receipt', to: '#receipt' },
        { id: 'archive', label: 'Archive', danger: true, onSelect: () => setConfirm(true) },
      ]} />
    </div>
    <p role="status" className="my-6">{result}</p>
    <Button variant="secondary" onClick={() => setPending(p => !p)}>Toggle pending</Button>
    <Pagination label="Example pages" page={page} pages={3} count="101 records" pending={pending}
      onPrevious={() => setPage(p => p - 1)} onNext={() => setPage(p => p + 1)} />
    <Button variant="secondary" onClick={() => setResult('Outside')}>Outside control</Button>
    <div className="mt-8">
      <ActionMenu label="Many actions" items={Array.from({ length: 30 }, (_, i) => ({
        id: String(i), label: `Action ${i + 1} with a long descriptive label that wraps on phones`,
        onSelect: () => setResult(`Selected ${i + 1}`),
      }))} />
      <ActionMenu label="No permitted actions" items={[{ id: 'hidden', label: 'Restricted', hidden: true, onSelect: () => setResult('WRONG') }]} />
    </div>
    {confirm && <Modal title="Archive Table 1?" onClose={() => setConfirm(false)}>
      <Button data-autofocus onClick={() => { setResult('Archived'); setConfirm(false); }}>Confirm archive</Button>
    </Modal>}
  </main>;
}
createRoot(document.getElementById('root')!).render(<BrowserRouter><Fixture /></BrowserRouter>);
