//! Lane descriptors served through the ABI, for BOTH row-store layouts,
//! against the geometry documented in abi.md §11/§18 and computed here
//! independently (no `RowLayout` helper is used for the expectation).
//!
//! Row-major (`lgj_rowstore_open`): lanes interleave, stride 512.
//! Facet-major (`lgj_rowstore_open_columnar`): lanes are contiguous blocks.

use super::*;
use crate::abi::{LgjElemKind, LGJ_FLAG_CONTIGUOUS, LGJ_FLAG_READABLE, LGJ_FLAG_WRITABLE};

#[derive(Clone, Copy)]
enum Shape {
    RowMajor,
    FacetMajor,
}

/// `(offset, len, elem_bytes, stride, kind)` for `lane`, from the docs.
fn documented(shape: Shape, n: u64, lane: u32) -> (u64, u64, u32, u32, LgjElemKind) {
    if lane == 0 {
        return (0, 512 * n, 1, 1, LgjElemKind::U8);
    }
    let (field, f) = ((lane - 1) / 32, u64::from((lane - 1) % 32));
    match (shape, field) {
        (Shape::RowMajor, 0) => (f * 16, n, 4, 512, LgjElemKind::U32),
        (Shape::RowMajor, 1) => (f * 16 + 4, n, 8, 512, LgjElemKind::U64),
        (Shape::RowMajor, 2) => (f * 16 + 12, n, 4, 512, LgjElemKind::U32),
        (Shape::FacetMajor, 0) => (f * 4 * n, n, 4, 4, LgjElemKind::U32),
        (Shape::FacetMajor, 1) => (128 * n + f * 8 * n, n, 8, 8, LgjElemKind::U64),
        (Shape::FacetMajor, 2) => (384 * n + f * 4 * n, n, 4, 4, LgjElemKind::U32),
        _ => unreachable!(),
    }
}

fn open_store(shape: Shape, n: u64) -> u64 {
    let mut h = 0u64;
    let st = match shape {
        Shape::RowMajor => unsafe { lgj_rowstore_open(n, 0x1A7E, &mut h) },
        // edge_classid 16 is outside 0..16: plain generator.
        Shape::FacetMajor => unsafe { lgj_rowstore_open_columnar(n, 0x1A7E, 16, 0, 1, &mut h) },
    };
    assert_eq!(st, LGJ_OK);
    h
}

fn describe(h: u64, lane: u32) -> LgjLaneDesc {
    let mut d = LgjLaneDesc::default();
    assert_eq!(call::lane_describe(h, lane, &mut d), LGJ_OK, "lane {lane}");
    d
}

#[test]
fn every_lane_descriptor_equals_the_documented_geometry_in_both_layouts() {
    for shape in [Shape::RowMajor, Shape::FacetMajor] {
        for n in [16u64, 257, 4096] {
            let h = open_store(shape, n);
            let mut info = LgjResourceInfo::default();
            assert_eq!(call::resource_info(h, &mut info), LGJ_OK);
            assert_eq!(info.lane_count, 97);
            let base = describe(h, 0).addr;
            let mut covered = vec![0u8; (512 * n) as usize];
            for lane in 0..97u32 {
                let d = describe(h, lane);
                let (off, len, eb, stride, kind) = documented(shape, n, lane);
                let ctx = format!("n={n} lane={lane}");
                assert_eq!(d.addr - base, off, "{ctx} offset");
                assert_eq!(d.len_elems, len, "{ctx} len_elems");
                assert_eq!(d.elem_bytes, eb, "{ctx} elem_bytes");
                assert_eq!(d.stride_bytes, stride, "{ctx} stride_bytes");
                assert_eq!(d.elem_kind, kind as u32, "{ctx} elem_kind");
                assert_eq!(
                    d.byte_len,
                    (len - 1) * u64::from(stride) + u64::from(eb),
                    "{ctx}"
                );
                let contiguous = stride == eb;
                assert_eq!(d.flags & LGJ_FLAG_CONTIGUOUS != 0, contiguous, "{ctx} flag");
                assert_eq!(d.flags & LGJ_FLAG_READABLE, LGJ_FLAG_READABLE, "{ctx}");
                assert_eq!(d.flags & LGJ_FLAG_WRITABLE, 0, "{ctx}");
                assert_eq!(d.owner, h, "{ctx} owner");
                assert_eq!(d.epoch, info.epoch, "{ctx} epoch");
                // The described span never leaves the allocation.
                assert!(off + d.byte_len <= 512 * n, "{ctx} span inside allocation");
                if lane != 0 {
                    for row in 0..len {
                        let at = (off + row * u64::from(stride)) as usize;
                        for b in 0..eb as usize {
                            covered[at + b] += 1;
                        }
                    }
                }
            }
            assert!(
                covered.iter().all(|&c| c == 1),
                "{shape_name} n={n}: 96 facet lanes disjoint and covering [0, 512n)",
                shape_name = match shape {
                    Shape::RowMajor => "row-major",
                    Shape::FacetMajor => "facet-major",
                }
            );
            // One past the end is refused.
            let mut d = LgjLaneDesc::default();
            assert_eq!(call::lane_describe(h, 97, &mut d), LGJ_ERR_INVALID_LANE);
            assert_eq!(lgj_close(h), LGJ_OK);
        }
    }
}

/// Facet-major facet lanes are whole blocks that abut: sorted by address they
/// start at 0 and each begins where the previous ended, ending at 512n.
#[test]
fn facet_major_facet_lanes_abut_and_end_at_512n() {
    for n in [16u64, 257, 4096] {
        let h = open_store(Shape::FacetMajor, n);
        let base = describe(h, 0).addr;
        let mut spans: Vec<(u64, u64)> = (1..97u32)
            .map(|l| {
                let d = describe(h, l);
                (d.addr - base, d.byte_len)
            })
            .collect();
        spans.sort();
        let mut cursor = 0u64;
        for (o, l) in spans {
            assert_eq!(o, cursor, "n={n}");
            cursor = o + l;
        }
        assert_eq!(cursor, 512 * n);
        assert_eq!(lgj_close(h), LGJ_OK);
    }
}
