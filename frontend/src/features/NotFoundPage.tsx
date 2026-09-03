import { Link } from 'react-router-dom';
import { Card } from '@/components/Card';

export function NotFoundPage() {
  return (
    <Card className="max-w-xl">
      <h1 className="text-heading text-text">No such screen</h1>
      <p className="mt-3 text-body text-text-dim">That address does not exist in the POS.</p>
      <Link to="/" className="mt-4 inline-block text-body text-info underline">
        Back to your home screen
      </Link>
    </Card>
  );
}
