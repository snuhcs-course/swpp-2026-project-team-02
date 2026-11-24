"""Portable filesystem setup for tests; unexpected OS failures remain failures."""
import errno


def symlink_or_skip(test, link, target, *, target_is_directory=False):
    """Exercise real links where supported, reporting absent privileges explicitly."""
    try:
        link.symlink_to(target, target_is_directory=target_is_directory)
    except NotImplementedError as error:
        test.skipTest(f"symlinks are unavailable: {error}")
    except OSError as error:
        if (getattr(error, "winerror", None) == 1314
                or error.errno in (errno.EPERM, errno.EACCES, errno.ENOSYS, errno.ENOTSUP)):
            test.skipTest(f"symlink creation is not permitted here: {error}")
        raise
