# shared permissive stub for runtime-opaque / external symbols
class _Stub:
    def __getattr__(self, _): return _Stub()
    def __call__(self, *a, **k): return _Stub()
    def __getitem__(self, k): return _Stub()
    def __setitem__(self, k, v): pass
    def __iter__(self): return iter(())
    def __len__(self): return 0
    def __bool__(self): return True
    def __eq__(self, o): return False
    def __hash__(self): return 0
    def __str__(self): return ''
    def __add__(self, o): return _Stub()
    __radd__ = __sub__ = __rsub__ = __mul__ = __rmul__ = __add__
    def __lt__(self, o): return False
    def __gt__(self, o): return False
    def __le__(self, o): return False
    def __ge__(self, o): return False
    def __contains__(self, k): return False
    def __enter__(self): return self
    def __exit__(self, *a): return False
