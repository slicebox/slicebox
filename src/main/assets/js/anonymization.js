(function () {
   'use strict';
}());

angular.module('slicebox.anonymization', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/anonymization', {
    templateUrl: '/assets/partials/anonymization.html',
    controller: 'AnonymizationCtrl'
  });
})

.controller('AnonymizationCtrl', function($scope, $http, $interval) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: $scope.confirmDeleteEntitiesFunction('/api/images/anonymizationkeys/', 'anonymization key(s)')
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.anonymizationKeyTable)) {
            $scope.callbacks.anonymizationKeyTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadAnonymizationKeyPage = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadUrl = '/api/images/anonymizationkeys?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            loadUrl = loadUrl + '&orderby=' + orderByProperty.toLowerCase();
            
            if (orderByDirection === 'ASCENDING') {
                loadUrl = loadUrl + '&orderascending=true';
            } else {
                loadUrl = loadUrl + '&orderascending=false';
            }
        }

        if (filter) {
            loadUrl = loadUrl + '&filter=' + encodeURIComponent(filter);
        }

        var loadPromise = $http.get(loadUrl);

        loadPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load anonymization keys: ' + error);
        });

        return loadPromise;
    };

});
