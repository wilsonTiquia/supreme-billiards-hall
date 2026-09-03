import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  archiveCategory,
  createCategory,
  fetchCategories,
  updateCategory,
} from '@/api/endpoints/categories';
import { queryKeys } from '@/api/queryKeys';
import { messageOf } from '@/api/errors';
import type { Category, CategoryRequest } from '@/api/types';
import { AdminPage } from '../AdminPage';
import { Card } from '@/components/Card';
import { Button } from '@/components/Button';
import { Field } from '@/components/Field';
import { Modal } from '@/components/Modal';
import { Spinner } from '@/components/Spinner';

export function CategoriesPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Category | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const categories = useQuery({ queryKey: queryKeys.categories, queryFn: fetchCategories });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: queryKeys.categories });
  }

  const save = useMutation({
    mutationFn: ({ id, body }: { id: string | null; body: CategoryRequest }) =>
      id ? updateCategory(id, body) : createCategory(body),
    onSuccess: () => {
      setError(null);
      setEditing(null);
      setCreating(false);
      refresh();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  const archive = useMutation({
    mutationFn: (id: string) => archiveCategory(id),
    onSuccess: () => {
      setError(null);
      refresh();
    },
    onError: (caught) => setError(messageOf(caught)),
  });

  return (
    <AdminPage
      title="Categories"
      intro="Archiving frees the name for reuse; products already sold under it are untouched."
      error={error ?? (categories.isError ? messageOf(categories.error) : null)}
    >
      <div className="mb-4 flex justify-end">
        <Button onClick={() => setCreating(true)}>New category</Button>
      </div>

      <Card>
        {categories.isPending ? (
          <div className="py-10 text-center">
            <Spinner label="Loading categories…" />
          </div>
        ) : (categories.data ?? []).length === 0 ? (
          <p className="py-6 text-center text-body text-text-dim">No categories yet.</p>
        ) : (
          <ul className="divide-y divide-border">
            {(categories.data ?? []).map((category) => (
              <li key={category.id} className="flex items-center justify-between gap-3 py-3">
                <div>
                  <div className="text-body text-text">{category.name}</div>
                  <div className="text-label text-text-dim">
                    Sort order {category.sortOrder ?? '—'}
                  </div>
                </div>
                <div className="flex gap-2">
                  <Button variant="secondary" onClick={() => setEditing(category)}>
                    Edit
                  </Button>
                  <Button
                    variant="secondary"
                    pending={archive.isPending && archive.variables === category.id}
                    onClick={() => archive.mutate(category.id)}
                  >
                    Archive
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {creating || editing ? (
        <CategoryForm
          category={editing}
          pending={save.isPending}
          onClose={() => {
            setEditing(null);
            setCreating(false);
          }}
          onSave={(body) => save.mutate({ id: editing?.id ?? null, body })}
        />
      ) : null}
    </AdminPage>
  );
}

function CategoryForm({
  category,
  pending,
  onClose,
  onSave,
}: {
  category: Category | null;
  pending: boolean;
  onClose: () => void;
  onSave: (body: CategoryRequest) => void;
}) {
  const [name, setName] = useState(category?.name ?? '');
  const [sortOrder, setSortOrder] = useState(
    category?.sortOrder != null ? String(category.sortOrder) : '',
  );

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (name.trim() === '') return;
    onSave({
      name: name.trim(),
      sortOrder: sortOrder.trim() === '' ? undefined : Number(sortOrder),
    });
  }

  return (
    <Modal title={category ? `Edit ${category.name}` : 'New category'} onClose={onClose}>
      <form onSubmit={handleSubmit} className="flex flex-col gap-6">
        <Field
          label="Name"
          value={name}
          data-autofocus
          onChange={(event) => setName(event.target.value)}
        />
        <Field
          label="Sort order (optional)"
          type="number"
          value={sortOrder}
          hint="Controls the order the counter sees."
          onChange={(event) => setSortOrder(event.target.value)}
        />
        <div className="flex justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={pending}>
            Save
          </Button>
        </div>
      </form>
    </Modal>
  );
}
