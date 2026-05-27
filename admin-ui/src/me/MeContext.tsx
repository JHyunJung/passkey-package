import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getMe } from '../api/client';
import type { Me } from '../api/types';

interface MeContextValue {
    me: Me | null;
    loading: boolean;
    reload: () => Promise<void>;
}

const MeContext = createContext<MeContextValue>({
    me: null,
    loading: true,
    reload: async () => {},
});

export function MeProvider({ children }: { children: ReactNode }) {
    const [me, setMe] = useState<Me | null>(null);
    const [loading, setLoading] = useState(true);

    const reload = async () => {
        try {
            setMe(await getMe());
        } catch {
            setMe(null);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { reload(); }, []);

    return <MeContext.Provider value={{ me, loading, reload }}>{children}</MeContext.Provider>;
}

export const useMe = () => useContext(MeContext);
