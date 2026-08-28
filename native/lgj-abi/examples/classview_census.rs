//! What the REAL ClassView provider answers, versus the fixture's constant.
//!
//! `cargo run --features ogar-classview --example classview_census`
fn main() {
    #[cfg(not(feature = "ogar-classview"))]
    println!("build with --features ogar-classview");

    #[cfg(feature = "ogar-classview")]
    {
        use lance_graph_contract::class_view::ClassView;
        use ogar_class_view::OgarClassView;
        use std::collections::BTreeMap;

        let view = OgarClassView::new();
        let ids: Vec<u16> = view.known_class_ids().collect();
        let mut hist: BTreeMap<usize, usize> = BTreeMap::new();
        for c in &ids {
            *hist.entry(view.fields(*c).len().min(32)).or_default() += 1;
        }
        println!("registered classes : {}", ids.len());
        println!(
            "distinct field counts (= distinct participation masks): {}",
            hist.len()
        );
        println!("\n  fields  classes");
        for (k, n) in &hist {
            println!("  {k:>6}  {n:>7}");
        }
        println!(
            "\nfixture would answer 0xFFFFFFFF (32 facets) for ALL {} classes.",
            ids.len()
        );
    }
}
