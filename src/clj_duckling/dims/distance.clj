(ns clj-duckling.dims.distance
  (:require
   [clj-duckling.util.engine :refer [export-value]]))

(defmethod export-value :distance [{:keys [value unit] :as token} _]
  {:type "value" :value value :unit unit})
